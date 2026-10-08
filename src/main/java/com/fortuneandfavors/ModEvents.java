package com.fortuneandfavors;

import com.fortuneandfavors.block.ElevatorBlock;
import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.economy.BlockValues;
import com.fortuneandfavors.economy.BossCodexManager;
import com.fortuneandfavors.economy.BossManager;
import com.fortuneandfavors.economy.BountyManager;
import com.fortuneandfavors.economy.ChestShopManager;
import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.CombatGear;
import com.fortuneandfavors.economy.CooperativeAchievementManager;
import com.fortuneandfavors.economy.CCEnchantments;
import com.fortuneandfavors.economy.AdvancedEnchantments;
import com.fortuneandfavors.economy.BossEmpowerment;
import com.fortuneandfavors.economy.CustomEnchantments;
import com.fortuneandfavors.economy.DailyLoginStreakManager;
import com.fortuneandfavors.economy.DailyWeeklyChallengeManager;
import com.fortuneandfavors.economy.DynamicContractsManager;
import com.fortuneandfavors.economy.ElytraLunge;
import com.fortuneandfavors.economy.ExpeditionManager;
import com.fortuneandfavors.economy.ExplosionRebuildManager;
import com.fortuneandfavors.economy.FirstEverRecordManager;
import com.fortuneandfavors.economy.ForgeOps;
import com.fortuneandfavors.economy.JobManager;
import com.fortuneandfavors.economy.LeaderboardManager;
import com.fortuneandfavors.economy.VanishManager;
import com.fortuneandfavors.economy.MiningZoneManager;
import com.fortuneandfavors.economy.RuneManager;
import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.economy.MiningPity;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.economy.PlayerRaidManager;
import com.fortuneandfavors.economy.PrisonManager;
import com.fortuneandfavors.economy.RaidGearManager;
import com.fortuneandfavors.economy.RareMobVariantManager;
import com.fortuneandfavors.economy.ServerDisasterManager;
import com.fortuneandfavors.economy.ServerNewspaperManager;
import com.fortuneandfavors.economy.ShopProgression;
import com.fortuneandfavors.economy.SkillManager;
import com.fortuneandfavors.economy.SonicBoom;
import com.fortuneandfavors.economy.SpawnerManager;
import com.fortuneandfavors.economy.StreakTrackerManager;
import com.fortuneandfavors.economy.TokenManager;
import com.fortuneandfavors.economy.WitherReworkManager;
import com.fortuneandfavors.economy.WormholeManager;
import com.fortuneandfavors.economy.ChestShopManager.ChestShop;
import com.fortuneandfavors.economy.ClaimManager.Claim;
import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.map.MapEditor;
import com.fortuneandfavors.menu.AutoSellMenu;
import com.fortuneandfavors.menu.BackpackMenu;
import com.fortuneandfavors.menu.BundleMenu;
import com.fortuneandfavors.menu.ChestShopConfigMenu;
import com.fortuneandfavors.menu.ChestShopMenu;
import com.fortuneandfavors.menu.ClaimPermsMenu;
import com.fortuneandfavors.menu.InfuserMenu;
import com.fortuneandfavors.menu.ItemForgeMenu;
import com.fortuneandfavors.menu.LootBoxMenu;
import com.fortuneandfavors.menu.LuckyPvpMenu;
import com.fortuneandfavors.menu.MysteryBoxMenu;
import com.fortuneandfavors.menu.RedeemerMenu;
import com.fortuneandfavors.menu.SpawnerMenu;
import com.fortuneandfavors.menu.TokenRenameMenu;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.ModPlatform;
import com.fortuneandfavors.util.PerfMonitor;
import com.fortuneandfavors.util.Safe;
import com.fortuneandfavors.util.SoundUtil;
import com.mojang.math.Transformation;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AfterDamage;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AfterDeath;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AllowDamage;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndTick;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.After;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.Before;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Disconnect;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Join;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction.Plane;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Display.BillboardConstraints;
import net.minecraft.world.entity.Display.TextDisplay;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class ModEvents {
   /** Players whose on-hit weapon proc is resolving right now; see the damage hook. */
   private static final java.util.Set<java.util.UUID> PROC_IN_FLIGHT = new java.util.HashSet<>();

   private static final Map<UUID, Long> crownRegenCooldown = new HashMap<>();
   private static final Map<UUID, Long> warlordRageCooldown = new HashMap<>();
   private static final Map<UUID, Long> evokerVitalityCooldown = new HashMap<>();
   private static final Map<UUID, Long> illusionDashCooldown = new HashMap<>();
   private static final Map<EntityType<?>, Item> MASK_SENSE_DROPS = Map.ofEntries(
      Map.entry(EntityTypes.ZOMBIE, Items.ROTTEN_FLESH),
      Map.entry(EntityTypes.HUSK, Items.ROTTEN_FLESH),
      Map.entry(EntityTypes.DROWNED, Items.ROTTEN_FLESH),
      Map.entry(EntityTypes.SKELETON, Items.BONE),
      Map.entry(EntityTypes.STRAY, Items.BONE),
      Map.entry(EntityTypes.CREEPER, Items.GUNPOWDER),
      Map.entry(EntityTypes.SPIDER, Items.STRING),
      Map.entry(EntityTypes.CAVE_SPIDER, Items.STRING),
      Map.entry(EntityTypes.ENDERMAN, Items.ENDER_PEARL),
      Map.entry(EntityTypes.WITCH, Items.REDSTONE),
      Map.entry(EntityTypes.BLAZE, Items.BLAZE_ROD),
      Map.entry(EntityTypes.SLIME, Items.SLIME_BALL),
      Map.entry(EntityTypes.MAGMA_CUBE, Items.MAGMA_CREAM),
      Map.entry(EntityTypes.PHANTOM, Items.PHANTOM_MEMBRANE),
      Map.entry(EntityTypes.GUARDIAN, Items.PRISMARINE_SHARD),
      Map.entry(EntityTypes.GHAST, Items.GHAST_TEAR),
      Map.entry(EntityTypes.VINDICATOR, Items.EMERALD),
      Map.entry(EntityTypes.EVOKER, Items.TOTEM_OF_UNDYING),
      Map.entry(EntityTypes.SHULKER, Items.SHULKER_SHELL),
      Map.entry(EntityTypes.ZOMBIFIED_PIGLIN, Items.GOLD_NUGGET),
      Map.entry(EntityTypes.PIGLIN, Items.GOLD_INGOT)
   );
   private static final Map<UUID, TextDisplay> MASK_SENSE_LABELS = new HashMap<>();
   // Perf: ResourceKey identity (not a per-tick string) is the dedup key, so the
   // dimension-change refresh below costs zero allocation on the common path.
   private static final Map<UUID, ResourceKey<?>> playerDimKeys = new HashMap<>();
   /** The expedition each body was last seen down, so the tab list can be rebroadcast when a
    *  run starts or ends - see the tab refresh in the per-player tick below. */
   private static final Map<UUID, String> playerExpeditionNames = new HashMap<>();
   private static final Map<UUID, Long> ARM_MSG_COOLDOWN = new HashMap<>();
   private static final Map<UUID, Long> stoneheartStandingSince = new HashMap<>();
   private static final Map<UUID, Long> PRISON_MSG_COOLDOWN = new HashMap<>();
   private static long goldenAppleNerfTick = 0L;

   private ModEvents() {
   }

   public static void register() {
      ServerPlayConnectionEvents.JOIN.register((Join)(handler, sender, server) -> {
         // First, before anything else looks at this player: enforce a timeout
         // handed out while they were offline, and put a moderator who dropped
         // mid-watch back where they were. Nothing below should run for a player
         // who is about to be turned straight around.
         if (Safe.veto("anticheat join", () -> com.fortuneandfavors.anticheat.AntiCheat.onJoin(handler.getPlayer()))) {
            return;
         }
         Safe.run("advanced enchant state restore", () -> AdvancedEnchantments.restorePlayerState(handler.getPlayer()));
         Safe.run("soulbound waiting notice", () -> {
            int waiting = AdvancedEnchantments.pendingSoulboundCount(handler.getPlayer().getUUID());
            if (waiting > 0) {
               Chat.raw(handler.getPlayer(), "§dYou have §f" + waiting + " soulbound item" + (waiting == 1 ? "" : "s") + "§d waiting - §f/ff claim-soulbound§d to retrieve " + (waiting == 1 ? "it" : "them") + " now, or they'll return on your next respawn.");
            }
         });
         Safe.run("creator title unlock", () -> com.fortuneandfavors.economy.TitleManager.unlockCreator(handler.getPlayer()));
         Safe.run("restore stale puppet", () -> BossManager.restoreStalePuppetState(handler.getPlayer()));
         Safe.run("snow realm rejoin", () -> BossManager.onRealmPlayerJoin(handler.getPlayer()));
         Safe.run("send puppet infos", () -> BossManager.sendPuppetInfosTo(handler.getPlayer()));
         Safe.run("duel rejoin restore", () -> DuelManager.onJoin(handler.getPlayer()));
         Safe.run("first join welcome", () -> {
            ServerPlayer jp = handler.getPlayer();
            CustomData jd = (CustomData)jp.get(DataComponents.CUSTOM_DATA);
            CompoundTag jt = jd != null ? jd.copyTag() : new CompoundTag();
            if (!jt.contains("ff_welcomed")) {
               jt.putBoolean("ff_welcomed", true);
               jp.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(jt));
               if (!ModConfig.playerPref(jp.getUUID(), "welcome")) {
                  Chat.raw(jp, "&6&lWelcome to Fortune & Favors!&r &7Use &e/menu&7 for everything, &e/ff help&7 for the wiki.");
                  if (ClaimManager.isAdminOp(jp)) {
                     Chat.raw(jp, "&7You're an admin: &e/ff config&7 tunes the whole server (claims, shops, bosses...).");
                  }
                  if (!ModConfig.playerPref(jp.getUUID(), "welcome_gui")) {
                     ModConfig.togglePlayerPref(jp.getUUID(), "welcome_gui");
                     com.fortuneandfavors.menu.FirstJoinMenu.open(jp);
                  }
               }
            }
         });
         Safe.run("shop discovery backfill", () -> ShopProgression.backfillDiscoveries(handler.getPlayer()));
         Safe.run("skill refund", () -> {
            ServerPlayer jp = handler.getPlayer();
            SkillManager.hadRefund(jp.getUUID());
         });
         Safe.run("warden return items", () -> {
            ServerPlayer jp = handler.getPlayer();
            List<ItemStack> wardenItems = BossManager.consumePendingWardenItems(jp.getUUID());
            long wardenExp = BossManager.consumePendingWardenExp(jp.getUUID());
            if (!wardenItems.isEmpty() || wardenExp > 0L) {
               for (ItemStack stack : wardenItems) {
                  InventoryHelper.giveOrDrop(jp, stack);
               }

               if (wardenExp > 0L) {
                  jp.giveExperiencePoints((int)wardenExp);
               }

               Chat.raw(jp, "§6§lThe Elder Warden's grip loosens...§r §7Its stolen loot has been returned!");
               SoundUtil.play(jp, ModSounds.TRANSFER);
               BossManager.saveWardenState(server);
            }
         });
         Safe.run("warden claim loot notice", () -> {
            ServerPlayer jp = handler.getPlayer();
            if (BossManager.consumeWardenClaimLootNotice(jp.getUUID())) {
               Chat.raw(jp, "§3The Elder Warden's stolen items are waiting in §f/claim loot§3 - collect them so they aren't forgotten.");
               BossManager.saveWardenState(server);
            }
         });
         Safe.run("daily login streak", () -> DailyLoginStreakManager.onJoin(handler.getPlayer()));
         Safe.run("playtime on join", () -> com.fortuneandfavors.economy.PlaytimeManager.onJoin(handler.getPlayer()));
         Safe.run("chair re-seat", () -> com.fortuneandfavors.block.ChairBlock.onConnect(handler.getPlayer()));
         Safe.run("millionaire check", () -> CooperativeAchievementManager.checkMillionaire(server, handler.getPlayer()));
         Safe.run("mod advancement root", () -> com.fortuneandfavors.economy.Advancements.grant(handler.getPlayer(), "root"));
         Safe.run("guild name note", () -> GuildManager.noteName(handler.getPlayer().getUUID(), handler.getPlayer().getName().getString()));
         // A moderator who logged out, crashed or was there when the server stopped mid-watch
         // gets their gear back from the kit's stash file here - see SpectateKit.onJoin.
         Safe.run("moderation kit recovery", () -> com.fortuneandfavors.anticheat.SpectateKit.onJoin(handler.getPlayer()));
      });
      // No illusion window (invisible caster, walking copies) may outlive the server.
      net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING
         .register(stoppingServer -> Safe.run("illusion teardown", () -> RaidGearManager.onServerStopping(stoppingServer)));
      ServerPlayConnectionEvents.DISCONNECT.register((Disconnect)(handler, server) -> {
         Safe.run("advanced enchant state save", () -> AdvancedEnchantments.persistPlayerState(handler.getPlayer()));
         Safe.run("cloak shield cleanup", () -> CombatGear.onPlayerDisconnect(handler.getPlayer()));
         Safe.run("duel disconnect", () -> DuelManager.onDisconnect(handler.getPlayer()));
         Safe.run("playtime on disconnect", () -> com.fortuneandfavors.economy.PlaytimeManager.onDisconnect(handler.getPlayer()));
         Safe.run("wormhole disconnect", () -> WormholeManager.onDisconnect(handler.getPlayer()));
         Safe.run("map editor disconnect", () -> MapEditor.onDisconnect(handler.getPlayer()));
         Safe.run("backpack jukebox cleanup", () -> com.fortuneandfavors.economy.BackpackJukebox.stop(handler.getPlayer()));
         Safe.run("vanish cleanup", () -> VanishManager.onDisconnect(handler.getPlayer()));
         Safe.run("illusion cleanup", () -> RaidGearManager.onPlayerLeave(handler.getPlayer()));
         Safe.run("clockwork gear cleanup", () -> com.fortuneandfavors.economy.ClockworkGear.onPlayerDisconnect(handler.getPlayer().getUUID()));
         Safe.run("magister gear cleanup", () -> com.fortuneandfavors.economy.MagisterGear.onPlayerDisconnect(handler.getPlayer().getUUID()));
         Safe.run(
            "void shaper gear cleanup",
            () -> com.fortuneandfavors.economy.VoidShaperGear.onPlayerLeave(handler.getPlayer().level().getServer(), handler.getPlayer())
         );
         Safe.run("sovereign gear cleanup", () -> com.fortuneandfavors.economy.SovereignGear.onPlayerDisconnect(handler.getPlayer().getUUID()));
         Safe.run("puppeteer gear cleanup", () -> com.fortuneandfavors.economy.PuppeteerGear.onLogout(handler.getPlayer()));
         // A mark outlives the body it was put on, and a thrown ring outlives the arm that threw
         // it, so leaving has to forget both.
         Safe.run("sea and sky gear cleanup", () -> com.fortuneandfavors.economy.SeaAndSkyGear.onPlayerDisconnect(handler.getPlayer().getUUID()));
         Safe.run("afk cleanup", () -> com.fortuneandfavors.economy.AfkManager.onDisconnect(handler.getPlayer()));
         Safe.run("chair seat cleanup", () -> com.fortuneandfavors.block.ChairBlock.onDisconnect(handler.getPlayer()));
         // A prisoner who logs out while inside leaves the block. The mode stashes the personal
         // inventory and hands out a uniform, so a body that logs out mid-sentence and comes back
         // would otherwise return to a mine with no gear and no way home - this is the softlock
         // guard, and it covers the chair and solitary as well as the open mine.
         Safe.run("prison logout", () -> {
            // A stop is not a quit: during a shutdown the sentence is left on the books so the next
            // boot resumes it, and only a real logout walks the prisoner out. Without the flag a
            // restart would disconnect everybody and quietly free every prisoner in the block.
            if (!com.fortuneandfavors.economy.PrisonManager.isServerStopping()
               && com.fortuneandfavors.economy.PrisonManager.isInPrison(handler.getPlayer())) {
               com.fortuneandfavors.economy.PrisonManager.leave(handler.getPlayer());
            }
         });
         Safe.run("raid betrayer logout", () -> PlayerRaidManager.onPlayerLogout(handler.getPlayer()));
         // A run does not outlive the body that was in it: logging out mid-expedition ends it, with
         // no loot banked and the site's own compass and pack taken back. See onPlayerLogout.
         Safe.run("expedition logout", () -> ExpeditionManager.onPlayerLogout(handler.getPlayer()));
         // ...and a lobby does not hold a seat for somebody who is not there, so the party's roster
         // loses the name and hands the crown down if it was the host's. See PartyManager.
         Safe.run("party logout", () -> com.fortuneandfavors.economy.PartyManager.onPlayerLogout(handler.getPlayer()));
         Safe.run("anticheat logout", () -> com.fortuneandfavors.anticheat.AntiCheat.onLogout(handler.getPlayer()));
         // The client's self-report is worth nothing once they are offline: it is a claim
         // about a running process, and that process has stopped. Held in memory only.
         Safe.run(
            "client context logout",
            () -> com.fortuneandfavors.anticheat.ClientContext.forget(handler.getPlayer().getUUID())
         );
         // Monotonic on purpose: a player who logs out having just lost their kit
         // must not overwrite the one snapshot that could still give it back.
         Safe.run("inventory snapshot on logout", () -> com.fortuneandfavors.util.LastInventoryHolder.snapshotIfChanged(handler.getPlayer()));
         Safe.run("dimension tracker forget", () -> com.fortuneandfavors.util.LastInventoryHolder.forgetDimension(handler.getPlayer().getUUID()));
         Safe.run("data autosave on logout", () -> com.fortuneandfavors.FortuneFavorsMod.saveAll(server));
      });
      net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN
         .register((net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AfterRespawn)(oldPlayer, newPlayer, alive) -> {
            if (newPlayer != null && !newPlayer.level().isClientSide()) {
               Safe.run("tab refresh on respawn", () -> com.fortuneandfavors.economy.TagManager.refreshTabList(newPlayer.level().getServer()));
               Safe.run("playtime respawn sync", () -> com.fortuneandfavors.economy.PlaytimeManager.onJoin(newPlayer));
               // The body that just respawned is not the body that died: a respawn is a
               // teleport with nothing in common with the position it came from, and the
               // anticheat has to be told or it reads arriving at spawn as a forged move
               // and the fall the last body was in the middle of as the new one's - two
               // findings, both of them produced by dying.
               Safe.run("anticheat respawn reset", () -> com.fortuneandfavors.anticheat.AntiCheat.onRespawn(newPlayer));
               // And a body that was being worn when the session ended: the hold is the
               // fight's own state and a restart takes the fight with it, so this is where
               // a player who logged out mid-possession gets their body back rather than
               // coming back a spectator with somebody else's camera. See
               // BossManager.repairStaleMindState.
               Safe.run("mind hold repair", () -> com.fortuneandfavors.economy.BossManager.repairStaleMindState(newPlayer));
               // A death the Mindbinder held: the pack was copied while it still existed and the
               // copy is handed to the body that came back. A respawn is not a teleport - vanilla
               // only carries an inventory across when the keep-inventory rule says to or the body
               // that died was a spectator - so without this the copy stayed in the corpse and the
               // player respawned with nothing. See BossManager.pendingRespawnPacks.
               Safe.run("corruption pack returned", () -> com.fortuneandfavors.economy.BossManager.grantRespawningCorruptionPack(newPlayer));
            }
         });
      com.fortuneandfavors.anticheat.AntiCheat.register();
      UseBlockCallback.EVENT.register((UseBlockCallback)(player, level, hand, hit) -> refusedUse(player, level, hand, hit));
      UseItemCallback.EVENT.register((UseItemCallback)(player, level, hand) -> refusedItem(player, level, hand));
      PlayerBlockBreakEvents.BEFORE
         .register((Before)(level, player, pos, state, blockEntity) -> Safe.allow("block-break", () -> {
            // Every "no" from this layer is a break the client has already predicted locally,
            // and vanilla's own resync - the one that puts the block back on the client that
            // broke it - lives inside the call this event is wrapped around. So a refusal here
            // without this line is a ghost block: the miner sees it vanish, the server still
            // has it, and it comes back on a relog. Wrapped around the whole handler rather
            // than added to each refusal, because there are a dozen refusals in it and the
            // one anybody adds next would be the one that forgot.
            boolean allowed = onBlockBreak(level, player, pos, state, blockEntity);
            if (!allowed && player instanceof ServerPlayer serverPlayer) {
               com.fortuneandfavors.util.ClientResync.block(serverPlayer, pos);
            }
            return allowed;
         }));
      PlayerBlockBreakEvents.AFTER
         .register((After)(level, player, pos, state, blockEntity) -> Safe.run("block-broken", () -> onBlockBroken(level, player, pos, state, blockEntity)));
      AttackEntityCallback.EVENT
         .register((AttackEntityCallback)(player, level, hand, entity, hitResult) -> refusedAttack(player, level, entity));
      AttackEntityCallback.EVENT
         .register((AttackEntityCallback)(player, level, hand, entity, hitResult) -> level.isClientSide()
            ? InteractionResult.PASS
            : Safe.result("excalibur slash", () -> com.fortuneandfavors.economy.ExcaliburSlash.onAttack(player, entity)));
      UseEntityCallback.EVENT
         .register((UseEntityCallback)(player, level, hand, entity, hitResult) -> refusedEntityUse(player, level, entity, hand));
      UseEntityCallback.EVENT
         .register((UseEntityCallback)(player, level, hand, entity, hitResult) -> Safe.result("wither king offer", () -> {
            if (!level.isClientSide()
               && entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss wr
               && WitherReworkManager.isEnabled()) {
               ItemStack heldWr = player.getItemInHand(hand);
               if (WitherReworkManager.onWitherRightClick((ServerPlayer)player, wr, heldWr)) {
                  return InteractionResult.SUCCESS;
               }
            }
            return InteractionResult.PASS;
         }));
      // The forgotten King sits in the throne hall of a Mirage Castle and does not get up for
      // anything: no arrow wakes him, no bar appears over his head, and the only thing in the
      // world he answers to is a player speaking to him.
      UseEntityCallback.EVENT
         .register((UseEntityCallback)(player, level, hand, entity, hitResult) -> Safe.result("throne king audience", () -> {
            if (!level.isClientSide()
               && player instanceof ServerPlayer sp
               && entity instanceof Mob throneKing
               && com.fortuneandfavors.economy.MirageCastleManager.isThroneKing(throneKing)) {
               return com.fortuneandfavors.economy.MirageCastleManager.wakeKing(sp, throneKing)
                  ? InteractionResult.SUCCESS
                  : InteractionResult.PASS;
            }
            return InteractionResult.PASS;
         }));
      // Guild score is read off a death by its own listener, registered before the
      // giant one below. The score calls used to sit inside that handler, so any
      // exception in a boss, revenant or raid system above them skipped both - and
      // wrapping them in Safe.run did not help, because a wrapper only protects its
      // own action. Nothing in the mod can starve this listener.
      ServerLivingEntityEvents.AFTER_DEATH.register((AfterDeath)(entity, source) -> Safe.run("guild score", () -> onScoreDeath(entity, source)));
      // The giant one: every death in the mod - boss loot ledgers, the Mindbinder's pack
      // restore, player feats, duels, streak, prison and expedition credit - is dispatched
      // from onDeath. It is registered here, on its own line, so a listener added above it
      // can never take it with it. Losing this line is silent: the mod compiles, the server
      // boots, and every death is simply unhandled.
      ServerLivingEntityEvents.AFTER_DEATH.register((AfterDeath)(entity, source) -> Safe.run("death", () -> onDeath(entity, source)));
      // The duel's manners window is read off chat itself. It has to be: "Kind Sir" is a
      // gg typed in the five seconds after a result, and typing anything means closing the
      // result screen first, so no screen event can be the trigger. The hook went in with
      // the achievement and was never wired to anything, which is a rule nobody could
      // satisfy - the achievement existed, the window was pinned by a self-test, and the
      // message that was supposed to earn it was never read.
      ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> Safe.run(
         "duel chat", () -> DuelManager.noteRematchChat(sender, message.signedContent())
      ));
      UseBlockCallback.EVENT.register((UseBlockCallback)(player, level, hand, hit) -> {
         if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            Safe.run("nicekeepinv track", () -> NiceKeepInventoryManager.setLastTouched(sp, hit.getBlockPos()));
         }

         return InteractionResult.PASS;
      });
      UseBlockCallback.EVENT.register((UseBlockCallback)(player, level, hand, hit) -> {
         if (!level.isClientSide() && level.getBlockState(hit.getBlockPos()).getBlock() instanceof BellBlock && level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
            Safe.run("bell raid summon", () -> {
               // Bell + Bad Omen = custom PLAYER raid (raiders hunt players, not villagers).
               // No glow/resonate datapack - that only spoiled pillager locations.
               if (sp.hasEffect(MobEffects.BAD_OMEN)) {
                  String err = PlayerRaidManager.triggerRaid(sp, null);
                  if (err != null) {
                     Chat.msg(sp, "&c" + err);
                  } else {
                     int levelAmplifier = sp.getEffect(MobEffects.BAD_OMEN).getAmplifier();
                     sp.removeEffect(MobEffects.BAD_OMEN);
                     PlayerRaidManager.setRaidDifficulty(levelAmplifier);
                  }
               }
            });
         }

         return InteractionResult.PASS;
      });
      // A creative player is out of reach of every fight, and the rule sits FIRST in the chain so
      // that no later rule (a rescue, a totem, a ceremony's arithmetic) is ever asked about a blow
      // that could not have landed. It is one rule for fifteen fights, which is the only shape
      // that survives a sixteenth being added: see BossEmpowerment.blocksCreative.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow(
            "creative player is out of reach",
            () -> !com.fortuneandfavors.economy.BossEmpowerment.blocksCreative(entity, source)
         ));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("king devour", () -> BossManager.onDevourDamage(entity, source, amount)));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("snow frost armor", () -> BossManager.onSnowArmorDamage(entity, source, amount)));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("golem armor", () -> BossManager.onGolemDamage(entity, source, amount)));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("mind mirror", () -> BossManager.onMindDamage(entity, source, amount)));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("mind control break", () -> BossManager.onFullControlHit(entity, source)));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("seize interrupt", () -> BossManager.onSeizeTargetHit(entity, source)));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("evoker totem", () -> BossManager.onFriendlyEvokerDamage(entity, source, amount)));
      // The Puppeteer's puppets keep ONE thing from the player they were copied from - a
      // bow, a shield or a totem. Two of the three need no code at all: the shield blocks
      // and the totem revives because the items in the copy's hands do. This hook only
      // narrates the totem, so a puppet's second life looks like a player's.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("puppet inheritance", () -> {
            if (entity instanceof ServerPlayer puppetVictim) {
               return com.fortuneandfavors.economy.PuppeteerManager.onPuppetLethal(puppetVictim, amount);
            }
            return true;
         }));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("duel no-death", () -> DuelManager.onDuelLethalDamage(entity, source, amount)));
      // Bow-only modes cancel melee outright, and the match report needs every
      // landed hit, not just the one that ended the fight.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("duel mode rules", () -> DuelManager.allowDuelDamage(entity, source)));
      ServerLivingEntityEvents.AFTER_DAMAGE
         .register((AfterDamage)(entity, source, amount, taken, blocked) -> Safe.run("duel match report", () -> DuelManager.onDuelAfterDamage(entity, source, taken)));
      // A guard holding a prisoner does not also hit them. The capture is the blow; what follows is
      // a reaction test against a clock, and an axe landing in the middle of it turns a minigame
      // into a coin flip. Cancelled outright rather than softened, so "the guards stop attacking you
      // while you work the cuffs" is a rule and not a damage number.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow(
            "prison guard holds fire",
            () -> !com.fortuneandfavors.economy.PrisonCellblock.holdsFire(entity, source.getEntity())
         ));
      // A guard's axe landing on a prisoner is an arrest, not only a wound: the first one cuffs them -
      // and the struggle to get out of the cuffs is a menu they have a reaction window to click
      // through - and once a prisoner has shown they can get out of cuffs, every hit after that is
      // five stacks out of their bag instead. See PrisonCellblock.onGuardHit.
      ServerLivingEntityEvents.AFTER_DAMAGE
         .register((AfterDamage)(entity, source, amount, taken, blocked) -> Safe.run("prison guard capture", () -> {
            if (entity instanceof ServerPlayer captured && taken > 0.0F
               && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker
               && com.fortuneandfavors.economy.PrisonCellblock.isGuard(attacker)) {
               com.fortuneandfavors.economy.PrisonCellblock.onGuardHit(captured, attacker);
            }
         }));
      // Every "you would die here" rule for a player, in one ordered decision.
      //
      // The walk owns the arithmetic (fatal after armor, absorption counted - the two figures
      // this used to get wrong and throw players out of fights they were winning), the totem the
      // player is holding (a rule in that walk, ahead of every territorial rule, so a totem is
      // the player's own answer by construction), and the order the rules run in. Before this
      // there were three copies of that arithmetic in three files and a fourth, hand-rolled copy
      // of vanilla's totem ritual in the duel gate. See LethalBlows.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("player lethal blow", () -> {
            if (entity instanceof ServerPlayer sp) {
               return com.fortuneandfavors.economy.LethalBlows.resolve(sp, source, amount);
            }
            return true;
         }));
      // Snow Queen's frozen realm: whatever would kill you (her attacks, the cold,
      // or the void) throws you out of the realm instead. You never die in there,
      // so there is nothing for NKI to lose - and once the realm is empty the
      // fight ends and she despawns (see BossManager.despawnReason).
      // The Time Lord's death is a scripted ceremony, not a death animation: the
      // killing blow is cancelled, he tries to stop time one last time, and the
      // attempt tears him apart (see TimeLordManager.onLethalDamage).
      // The King Wither Skeleton's killing blow is held for his death to play standing (see
      // BossManager.onKingLethal). The final, real kill is genericKill and passes straight through.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("king lethal blow", () -> {
            if (source.is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)) {
               return Boolean.TRUE;
            }
            Boolean result = com.fortuneandfavors.economy.BossManager.onKingLethal(entity, amount);
            return result == null ? Boolean.TRUE : result;
         }));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("time lord lethal blow", () -> {
            Boolean result = com.fortuneandfavors.economy.TimeLordManager.onLethalDamage(entity, amount);
            return result == null ? Boolean.TRUE : result;
         }));
      // Pocket-Watch: while its bubble holds the world still, nothing inside takes
      // damage - every blow is banked and paid out the instant time resumes (see
      // TimeLordManager.tryDelayDamage).
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("pocket watch delay", () -> {
            if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
               return !com.fortuneandfavors.economy.TimeLordManager.tryDelayDamage(living, source, amount);
            }
            return true;
         }));
      // The Puppeteer's death is a scripted ceremony too, and the longest one in the
      // mod: his strings are cut one at a time before anything drops. The killing
      // blow is absorbed so the hands can be emptied on screen (see
      // PuppeteerManager.onLethalDamage).
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("puppeteer lethal blow", () -> {
            Boolean result = com.fortuneandfavors.economy.PuppeteerManager.onLethalDamage(entity, amount);
            return result == null ? Boolean.TRUE : result;
         }));
      // The Empty Mask: the blow that would have killed its wearer does not. The mask
      // is spent instead - a decoy wearing their face drops where they were standing
      // and they get a few seconds with nothing able to see them.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("empty mask escape", () -> {
            if (entity instanceof ServerPlayer masked
               && amount >= masked.getHealth()
               && com.fortuneandfavors.economy.PuppeteerGear.canEscape(masked)) {
               return !com.fortuneandfavors.economy.PuppeteerGear.escape(masked);
            }
            return true;
         }));
      // A projectile that a Pocket-Watch was holding lands through invulnerability
      // frames: stop time, empty a quiver, and every arrow counts.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("pocket watch iframes", () -> {
            if (com.fortuneandfavors.economy.TimeLordManager.bypassesIframes(source) && entity.invulnerableTime > 0) {
               entity.invulnerableTime = 0;
            }
            return true;
         }));
      // A Blood Revenant (or a Night Swarm bat) can never hurt the player it
      // answers to, whatever its vanilla goals decide between our ticks - the
      // client-side damage prediction is what actually made them bite their owner.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("blood servant loyalty", () -> {
            if (entity instanceof ServerPlayer victim
               && com.fortuneandfavors.economy.ScarletGear.isFriendlyAttack(victim, source.getEntity())) {
               return false;
            }
            return true;
         }));
      // The Scarlet Devil's death is a ceremony too: the killing blow is cancelled
      // and played out, so her last blood rain and her final line actually happen.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("scarlet devil lethal blow", () -> {
            Boolean result = com.fortuneandfavors.economy.ScarletDevilManager.onLethalDamage(entity, amount);
            return result == null ? Boolean.TRUE : result;
         }));
      // The Clockwork King's death is a shutdown ceremony: the killing blow is
      // cancelled so the arsenal powers down one machine at a time before the loot
      // lands, instead of the body simply falling over and skipping the sequence.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("clockwork king lethal blow", () -> {
            Boolean result = com.fortuneandfavors.economy.ClockworkKingManager.onLethalDamage(entity, amount);
            return result == null ? Boolean.TRUE : result;
         }));
      // The Starbound Magister, the Void Shaper and the Emerald Sovereign all
      // die by ceremony, for the same reason the others do: the killing blow is
      // cancelled so the sequence and the loot fire exactly once.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("starbound magister lethal blow", () -> {
            Boolean result = com.fortuneandfavors.economy.StarboundMagisterManager.onLethalDamage(entity, amount);
            return result == null ? Boolean.TRUE : result;
         }));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("void shaper lethal blow", () -> {
            Boolean result = com.fortuneandfavors.economy.VoidShaperManager.onLethalDamage(entity, amount);
            return result == null ? Boolean.TRUE : result;
         }));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("emerald sovereign lethal blow", () -> {
            Boolean result = com.fortuneandfavors.economy.EmeraldSovereignManager.onLethalDamage(entity, amount);
            return result == null ? Boolean.TRUE : result;
         }));
      // The sea boss and the sky boss die on camera too, for the same reason: the killing blow is
      // cancelled so a dozen hazards stop landing on a body that has already fallen, and the
      // sequence that replaces it is the only route that pays the loot.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("drowned sovereign lethal blow", () -> {
            Boolean result = com.fortuneandfavors.economy.DrownedSovereignManager.onLethalDamage(entity, amount);
            return result == null ? Boolean.TRUE : result;
         }));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("gale warden lethal blow", () -> {
            Boolean result = com.fortuneandfavors.economy.GaleWardenManager.onLethalDamage(entity, amount);
            return result == null ? Boolean.TRUE : result;
         }));
      // The Warden's Mantle's second wind. Registered AHEAD of the player-lethal walk on purpose:
      // a rule that only gets asked after another rule has already cancelled the blow is a rule
      // that is never asked at all, and the item's one promise is that it answers a fall which
      // would otherwise have killed you.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("warden's mantle second wind", () -> {
            Boolean result = com.fortuneandfavors.economy.SeaAndSkyGear.onLethalFallDamage(entity, source, amount);
            return result == null ? Boolean.TRUE : result;
         }));
      // The Elder Warden dies on camera too. He was the one boss whose death was only
      // handled afterwards, so the killing hit landed and the sequence played over a
      // body that had already fallen - a boss with no death animation and no i-frames
      // to anybody watching it. The blow is caught, he is held up while the death is
      // played on the living body, and then the body dies for real so the ordinary
      // death path (and its loot) still runs once (see BossManager.onWardenLethalDamage).
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("elder warden lethal blow", () -> {
            Boolean result = BossManager.onWardenLethalDamage(entity, amount);
            return result == null ? Boolean.TRUE : result;
         }));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("snow realm fatal blow", () -> {
            if (entity instanceof ServerPlayer sp) {
               return BossManager.onSnowRealmLethalDamage(sp, source, amount);
            }
            return true;
         }));
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow("advanced enchantment damage", () -> {
            if (!(entity instanceof ServerPlayer sp)) {
               return true;
            }
            if (AdvancedEnchantments.handleShieldBlock(sp, source)) {
               return false;
            }
            return amount < sp.getHealth() || !AdvancedEnchantments.tryCurseOfUndying(sp);
         }));
      // Any hurt that actually lands while the Illusioner's Spellbook is active shatters
      // the illusions and puts the spellbook on cooldown (read after the hit, so a
      // cancelled or shield-blocked one does not count).
      ServerLivingEntityEvents.AFTER_DAMAGE.register((AfterDamage)(entity, source, amount, taken, blocked) -> {
         if (entity instanceof ServerPlayer sp && !blocked) {
            Safe.run("illusion break on hit", () -> RaidGearManager.onPlayerHit(sp));
         }
      });
      ServerLivingEntityEvents.ALLOW_DAMAGE.register((AllowDamage)(entity, source, amount) -> {
         if (entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss wr
            && !entity.level().isClientSide()
            && WitherReworkManager.isEnabled()
            && amount >= entity.getHealth()
            && entity.level() instanceof net.minecraft.server.level.ServerLevel wrLevel
            && !wr.isInvulnerableTo(wrLevel, source)) {
            return Safe.allow("wither death capture", () -> WitherReworkManager.onWitherDamageAttempt(wr, source, amount));
         }
         return true;
      });
      // The Ender Dragon's one gate. Three jobs in one ordered place: the last-stand finale owns
      // the body while it runs, the death ceremony owns it for its five seconds, and a blow that
      // would take a phase-three dragon to its floor does not land at all - it starts the finale.
      // Everything else is vanilla's, including the kill in the last stand, which is one hit.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow(
            "ender dragon damage",
            () -> com.fortuneandfavors.economy.EnderDragonManager.onDragonDamage(entity, source, amount)
         ));
      // And the crystals' own gate. Two of the roster's types are defined by what they refuse - the
      // forcefield eats projectiles and the caged crystal eats every other blow - and while the
      // ten-thousand crystal stands, nothing else in the ring can be hurt at all. A refusal that
      // says nothing is the same ghost-block bug as a vanished placement, so the manager answers on
      // the action bar as well as in the event.
      ServerLivingEntityEvents.ALLOW_DAMAGE
         .register((AllowDamage)(entity, source, amount) -> Safe.allow(
            "end crystal damage",
            () -> com.fortuneandfavors.economy.EnderDragonManager.onCrystalDamage(entity, source, amount)
         ));
      // "Free the End" is granted off the dragon's *death*, not off the fight's own ceremony. The
      // reworked death grants it too, but the rework spends the killing blow as a scripted
      // genericKill with no attacker, so vanilla's player_killed_entity trigger can never fire -
      // and a dragon killed any other way (a rematch, the rework switched off, /kill) would end on
      // an achievement nobody received. Registered here, on its own line, so it cannot be taken
      // with any other listener: losing it is silent.
      ServerLivingEntityEvents.AFTER_DEATH.register((AfterDeath)(entity, source) -> Safe.run(
         "free the end",
         () -> com.fortuneandfavors.economy.EnderDragonManager.onDragonDeath(entity)
      ));
      ServerLivingEntityEvents.AFTER_DAMAGE.register((AfterDamage)(entity, source, amount, taken, blocked) -> Safe.run("boss damage", () -> {
         if (BossManager.isBoss(entity) && source.getEntity() instanceof ServerPlayer p) {
            BossManager.recordDamage(entity, p);
            BossManager.onBossHit(entity);
            BossManager.onMindbinderBossHit(entity);
         }

         if (entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss wr) {
            WitherReworkManager.onWitherDamaged(wr, source, amount);
         }

         // Landing any hit on the Time Lord during his 1% judgment saves you.
         if (entity instanceof net.minecraft.world.entity.LivingEntity timeLordHit
            && com.fortuneandfavors.economy.TimeLordManager.isTimeLord(timeLordHit)
            && source.getEntity() instanceof ServerPlayer timeLordHitter) {
            com.fortuneandfavors.economy.TimeLordManager.onJudgmentHit(timeLordHit, timeLordHitter);
         }

         // The Puppeteer's counterplay, in one line: landing a blow on him cuts
         // whatever string he has tied to you. That is the whole answer to being
         // threaded, so it has to be the same hook a normal hit takes.
         if (entity instanceof net.minecraft.world.entity.LivingEntity puppetBoss
            && com.fortuneandfavors.economy.PuppeteerManager.isPuppeteer(puppetBoss)
            && source.getEntity() instanceof ServerPlayer puppetHitter) {
            com.fortuneandfavors.economy.PuppeteerManager.onBossHit(puppetBoss, puppetHitter);
         }

         // The Gale Warden's two answers to being hit, and neither of them is BossManager's:
         // while he is braced (Gale Counter) the blow is thrown back at whoever landed it, and
         // while he is charging (Absolute Momentum) it is banked toward interrupting him - so
         // his final attack is a damage check rather than a timer nobody can influence. Read
         // here because this is the only place a blow may be scaled or answered exactly once.
         if (entity instanceof net.minecraft.world.entity.LivingEntity galeBoss
            && com.fortuneandfavors.economy.GaleWardenManager.isGaleWarden(galeBoss)) {
            com.fortuneandfavors.economy.GaleWardenManager.onBossDamaged(galeBoss, taken, source.getEntity());
         }

         if (entity instanceof ServerPlayer hurtPlayer) {
            // The Puppeteer's shared counterplay: hitting the person he is winding a third
            // string onto breaks it before it lands. Same shape as the Mindbinder's "a
            // friend shakes you free", and deliberately not a damage cancellation - the
            // hit still lands, breaking the hold is just what it also does.
            Safe.run(
               "puppeteer teammate break",
               () -> com.fortuneandfavors.economy.PuppeteerManager.onTeammateHit(hurtPlayer, source.getEntity())
            );
            WitherReworkManager.onPlayerDamaged(hurtPlayer, source, taken);
            // The Mechanical Heart only repairs out of combat, so it needs to
            // know when you were last hit.
            Safe.run("clockwork heart hit", () -> com.fortuneandfavors.economy.ClockworkGear.onPlayerDamaged(hurtPlayer));
            // The Astral Mantle can blink you backward out of a heavy blow.
            Safe.run("astral mantle blink", () -> com.fortuneandfavors.economy.MagisterGear.onPlayerDamaged(hurtPlayer, taken));
            // Taking a hit breaks a Blood Prism channel - the ritual is meant to
            // be a stand-still duel, and standing still under fire is the cost.
            if (taken > 0.0F) {
               Safe.run("blood ritual break", () -> com.fortuneandfavors.economy.ScarletGear.onCasterDamage(hurtPlayer));
            }
         }
         if (entity instanceof ServerPlayer hurt) {
            BossManager.recallFriendlies(hurt);
            // Getting hit arms the 3s wormhole combat tag (any damage source
            // with an attacker - falls/lava don't tag you, getting shot does).
            if (taken > 0.0F && source.getEntity() != null && source.getEntity() != hurt) {
               Safe.run("wormhole combat tag", () -> WormholeManager.tagCombat(hurt));
            }
         }

         if (source.getEntity() instanceof ServerPlayer hitter) {
            BossManager.recallFriendlies(hitter);
            // One proc at a time per player. The damage a proc deals is credited to the same player,
            // so it came straight back through this hook as a fresh "landed hit": a Gauntlet ram
            // through four mobs refilled its own charge and fired a second ram from inside the first,
            // and every weapon below could do the same with its own effects. While a proc is resolving,
            // the hits it causes do not count as blows.
            if (PROC_IN_FLIGHT.add(hitter.getUUID())) {
               try {
                  // The Clockwork Gauntlet winds its Overdrive on landed hits only, so
                  // the charge is driven from here rather than from a swing event: a
                  // swing that misses or is blocked must not build it.
                  if (attackerHeldGauntlet(hitter) && entity instanceof net.minecraft.world.entity.LivingEntity gauntletVictim) {
                     Safe.run("clockwork gauntlet hit", () -> com.fortuneandfavors.economy.ClockworkGear.onGauntletHit(hitter, gauntletVictim));
                  }
                  if (entity instanceof net.minecraft.world.entity.LivingEntity bladeVictim) {
                     if (ModItems.isStarpiercer(hitter.getMainHandItem())) {
                        Safe.run("starpiercer hit", () -> com.fortuneandfavors.economy.MagisterGear.onStarpiercerHit(hitter, bladeVictim));
                     }
                     if (ModItems.isVoidReaver(hitter.getMainHandItem())) {
                        Safe.run("void reaver hit", () -> com.fortuneandfavors.economy.VoidShaperGear.onReaverHit(hitter, bladeVictim));
                     }
                     // The End's set: every Voidfang blow leaves a mark that detonates on the
                     // third, and an Enderheart blow landed out of a fall leaves an End
                     // shockwave behind it. Both are read off the weapon in the hand, which
                     // is the only thing the two have in common with every other hit here.
                     if (ModItems.isAnyVoidfang(hitter.getMainHandItem())) {
                        Safe.run("voidfang hit", () -> com.fortuneandfavors.economy.EnderGear.onVoidfangHit(hitter, bladeVictim));
                     }
                     if (ModItems.isAnyEnderheart(hitter.getMainHandItem())) {
                        Safe.run("enderheart hit", () -> com.fortuneandfavors.economy.EnderGear.onEnderheartHit(hitter, bladeVictim));
                     }
                     // The sea set's three on-hit halves: the Grasp's knockback passive and its
                     // dive, the Chain's mark and Deep Strike, the Skybreaker's Momentum, Updraft
                     // and Downforce. Read off the hand that landed the blow, like every other one.
                     if (ModItems.isLeviathansGrasp(hitter.getMainHandItem())) {
                        Safe.run("leviathan's grasp hit", () -> com.fortuneandfavors.economy.SeaAndSkyGear.onGraspHit(hitter, bladeVictim));
                     }
                     if (ModItems.isAbyssalChain(hitter.getMainHandItem())) {
                        Safe.run("abyssal chain hit", () -> com.fortuneandfavors.economy.SeaAndSkyGear.onChainHit(hitter, bladeVictim));
                     }
                     if (ModItems.isSkybreaker(hitter.getMainHandItem())) {
                        Safe.run("skybreaker hit", () -> com.fortuneandfavors.economy.SeaAndSkyGear.onSkybreakerHit(hitter, bladeVictim));
                     }
                  }
               } finally {
                  PROC_IN_FLIGHT.remove(hitter.getUUID());
               }
            }
            // A Starfall arrow landing: the arrow carries the weapon that fired it for
            // its whole flight, so the Astral Mark is read from the arrow rather than
            // from whatever the archer happens to be holding when it lands.
            if (entity instanceof net.minecraft.world.entity.LivingEntity arrowVictim
               && source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow) {
               Safe.run("starfall arrow", () -> com.fortuneandfavors.economy.EnderGear.onArrowHit(hitter, arrowVictim, arrow));
            }
         }

         ServerPlayer attacker = playerAttackerOf(source);
         if (entity instanceof ServerPlayer victim && attacker != null && attacker != victim) {
            BossManager.recordAggressor(victim, attacker);
         }

         if (entity instanceof ServerPlayer victim2 && taken > 0.0F) {
            BossManager.onSharedMindDamage(victim2, taken);
         }

         // The misc feats: half a heart, the sixty-second chain, and the Puppeteer's
         // string-tipped arrows, which tie a string to whoever one lands on.
         if (taken > 0.0F) {
            Safe.run(
               "player feats damage",
               () -> com.fortuneandfavors.economy.PlayerFeatTracker.onDamage(entity, source, taken)
            );
         }
         Safe.run(
            "puppeteer string arrow",
            () -> com.fortuneandfavors.economy.PuppeteerManager.onStringArrowHit(source, entity)
         );
      }));
      // Custom enchant tomes are intentionally NOT in generated chest loot -
      // every random chest (dungeon, mineshaft, temple...) used to roll a tome
      // pool, flooding exploration with them. They stay exclusive to
      // librarians (VillagerTradeMixin), boss drops, forges, loot boxes and
      // /ff give.
      ServerTickEvents.END_SERVER_TICK
         .register(
            (EndTick)server -> {
               Safe.run("excalibur slash", () -> com.fortuneandfavors.economy.ExcaliburSlash.tick(server));
               // The shared particle budget for every boss effect, zeroed once a tick. This call is
               // the whole reason the budget works, and it was missing: `BossVfx` spends per player
               // per tick and stops drawing when a player's share is gone, so with nothing ever
               // clearing it the first effect of the session spent the floor and every boss effect
               // for the rest of that session drew NOTHING. One missing line, and the symptom is
               // "the boss has no VFX" - including the Mirage Castle's collapse, which was routed
               // through this same budget.
               Safe.run("boss vfx budget", () -> com.fortuneandfavors.economy.BossVfx.beginTick());
               Safe.run("vfx tick", () -> VfxManager.tick(server));
               Safe.run("ender gear tick", () -> com.fortuneandfavors.economy.EnderGear.tick(server));
               Safe.run("spawner tick", () -> SpawnerManager.tick(server));
               Safe.run("friendly skeleton tick", () -> BossManager.tickFriendly(server));
               Safe.run("ice staff spray", () -> BossManager.tickIceStaffSpray(server));
               Safe.run("frozen mobs", () -> BossManager.tickFrozenMobs(server));
               Safe.run("snow realm escape", () -> BossManager.tickRealmEscapes(server));
               Safe.run("blood revenant tick", () -> BossManager.tickRevenants(server));
               Safe.run("time lord tick", () -> com.fortuneandfavors.economy.TimeLordManager.tick(server));
               Safe.run("scarlet devil tick", () -> com.fortuneandfavors.economy.ScarletDevilManager.tick(server));
               Safe.run("clockwork king tick", () -> com.fortuneandfavors.economy.ClockworkKingManager.tick(server));
               Safe.run("clockwork gear tick", () -> com.fortuneandfavors.economy.ClockworkGear.tick(server));
               Safe.run("starbound magister tick", () -> com.fortuneandfavors.economy.StarboundMagisterManager.tick(server));
               Safe.run("void shaper tick", () -> com.fortuneandfavors.economy.VoidShaperManager.tick(server));
               Safe.run("void shaper loose blocks", () -> com.fortuneandfavors.economy.VoidShaperManager.tickLoose(server));
               Safe.run("boss hazards", () -> com.fortuneandfavors.economy.Hazards.tick());
               Safe.run("emerald sovereign tick", () -> com.fortuneandfavors.economy.EmeraldSovereignManager.tick(server));
               Safe.run("magister gear tick", () -> com.fortuneandfavors.economy.MagisterGear.tick(server));
               Safe.run("void shaper gear tick", () -> com.fortuneandfavors.economy.VoidShaperGear.tick(server));
               Safe.run("puppeteer tick", () -> com.fortuneandfavors.economy.PuppeteerManager.tick(server));
               Safe.run("puppeteer gear tick", () -> com.fortuneandfavors.economy.PuppeteerGear.tick(server));
               // The two newest fights, and the six legendaries they drop. The gear tick is not
               // optional: the Warden's Mantle has no right-click at all, so every one of its
               // abilities - the slow fall, the dash, the updraft, the tailwind - exists only
               // here, and a missing registration is four dead abilities rather than one.
               Safe.run("drowned sovereign tick", () -> com.fortuneandfavors.economy.DrownedSovereignManager.tick(server));
               Safe.run("gale warden tick", () -> com.fortuneandfavors.economy.GaleWardenManager.tick(server));
               Safe.run("sea and sky gear tick", () -> com.fortuneandfavors.economy.SeaAndSkyGear.tick(server));

               Safe.run("sovereign gear tick", () -> com.fortuneandfavors.economy.SovereignGear.tick(server));
               Safe.run("scarlet gear tick", () -> com.fortuneandfavors.economy.ScarletGear.tick(server));
               Safe.run("loot box tick", () -> LootBoxMenu.tickAll(server));
               Safe.run("mystery box tick", () -> com.fortuneandfavors.menu.MysteryBoxMenu.tickAll(server));
               Safe.run("betrayal tick", () -> com.fortuneandfavors.menu.BetrayalMenu.tickAll(server));
               Safe.run("lucky pvp reveal tick", () -> LuckyPvpMenu.tickAll(server));
               Safe.run("staff stone tick", () -> BossManager.tickStaffStones(server));
               Safe.run("fist wave tick", () -> BossManager.tickFistWaves(server));
               Safe.run("golem crumble tick", () -> BossManager.tickGolemCrumble(server));
               Safe.run("mind dominate tick", () -> BossManager.tickDominated(server));
               Safe.run("staff seize tick", () -> BossManager.tickStaffSeize(server));
               Safe.run("mind summon tick", () -> BossManager.tickMindSummons(server));
               Safe.run("explosion rebuild tick", () -> ExplosionRebuildManager.tick(server));
               Safe.run("wither rework tick", () -> WitherReworkManager.tick(server));
               // One layer for every boss in the mod: the aura, the wind-up and the surge. Its
               // damage half is not here at all - that is read in the damage pipeline, which is
               // the only place a boss's blow can be scaled exactly once.
               Safe.run("boss empowerment", () -> BossEmpowerment.tick(server));
               Safe.run("nicekeepinv tick", () -> NiceKeepInventoryManager.tick(server));
               // Vanish is re-asserted on a clock rather than set once - see VanishManager.tick.
               Safe.run("vanished players", () -> VanishManager.tick(server));
               // The End: the held spawn, the rift, and the dragon's three phases.
               Safe.run("ender dragon tick", () -> com.fortuneandfavors.economy.EnderDragonManager.tick(server));
               // The boss themes' claim on the room's music, ticked down - see BossMusic.
               Safe.run("boss music tick", () -> com.fortuneandfavors.economy.BossMusic.tick(server));
               Safe.run("wanted aura tick", () -> com.fortuneandfavors.economy.BountyManager.tickAura(server));
               Safe.run("mind puppet tick", () -> BossManager.tickMindPuppets(server));
               Safe.run("mind hallucination tick", () -> BossManager.tickMindHallucinations(server));
               Safe.run("mind echo tick", () -> BossManager.tickMindEchoes(server));
               // And the net under all of them: a puppet body nobody is driving does not get to
               // stand in the world forever. See BossManager.tickPuppetSweep.
               Safe.run("puppet sweep", () -> BossManager.tickPuppetSweep(server));
               Safe.run("test corruption tick", () -> BossManager.tickTestCorruption(server));
               Safe.run("warden global tick", () -> BossManager.tickWardenGlobal(server));
               Safe.run("disaster tick", () -> ServerDisasterManager.tick(server));
               // Perf: the five systems below are wall-clock schedulers (day
               // rollovers, 4h/30m shortages, weekly draws). Polling them once
               // per tick burns System.currentTimeMillis/LocalDate on the hot
               // path for nothing; 1s resolution is still 60x finer than any
               // of their intervals.
               Safe.run("player raid tick", () -> PlayerRaidManager.tick(server));
               if (server.getTickCount() % 20L == 0L) {
                  Safe.run("contracts tick", () -> DynamicContractsManager.tick(server));
                  Safe.run("lottery tick", () -> com.fortuneandfavors.economy.LotteryManager.tick(server));
                  Safe.run("bank interest tick", () -> com.fortuneandfavors.economy.BankManager.tick(server));
                  // The exchange runs on the wall clock, so nothing here advances a price - this is
                  // only the redraw: when the five-minute grid rolls over, a chart somebody has
                  // open updates itself instead of waiting for its owner to click something.
                  Safe.run("market clock", () -> com.fortuneandfavors.menu.StockMenus.tick(server));
                  Safe.run("newspaper tick", () -> ServerNewspaperManager.tick(server));
               }
               Safe.run("challenge rotation tick", () -> DailyWeeklyChallengeManager.tick(server));
               Safe.run("guild mine tick", () -> GuildManager.tickMines(server));
               if (server.getTickCount() % 4L == 0L) {
                  Safe.run("guild war particles", () -> GuildManager.tickEnemyParticles(server));
               }
               // Its own slot in the minute cycle, so it does not land on the same tick as the
               // mining zones' containment sweep (see MINUTE_OFFSETS).
               if (FortuneFavorsMod.due(
                  server.getTickCount(), FortuneFavorsMod.MINUTE_CYCLE_TICKS, FortuneFavorsMod.MINUTE_MAILBOX
               )) {
                  Safe.run("guild mail cleanup", () -> GuildManager.tickMailboxCleanup(server));
               }
               // Anti-crash rollback: every 2 minutes, persist all mod data AND
               // refresh inventory snapshots, so a crash or /ff restore always
               // has a recent state instead of rolling back a whole session.
               if (server.getTickCount() % 2400L == 0L) {
                  // Snapshots first, then persist - the other order saved the
                  // previous cycle's snapshots, so the file on disk always lagged
                  // a full two minutes behind the live state.
                  Safe.run("inventory snapshot all", () -> com.fortuneandfavors.util.LastInventoryHolder.snapshotAll(server));
                  Safe.run("periodic data autosave", () -> com.fortuneandfavors.FortuneFavorsMod.saveAll(server));
               }

               boolean perfOn = PerfMonitor.isEnabled();
               for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                  // Tab-list dimension markers go stale when a player changes
                  // dimension (or dies/respawns) - rebroadcast so the old
                  // prefix/color is replaced immediately. Identity-compares the
                  // ResourceKey (no string allocation, no toString per tick).
                  Safe.run("tab dimension refresh", () -> {
                     ResourceKey<?> dimNow = player.level().dimension();
                     ResourceKey<?> dimPrev = playerDimKeys.put(player.getUUID(), dimNow);
                     if (dimPrev != null && dimPrev != dimNow) {
                        com.fortuneandfavors.economy.TagManager.refreshTabList(server);
                     }
                  });
                  // The same staleness, one state further in. A run owns a body whether or not the
                  // level under it changed - an explorer is teleported into the site and out of it
                  // from a menu, from a pad, from a death and from a disconnect, and the marker has
                  // to follow all of those - so the tab list is told when the run changes as well.
                  Safe.run("tab expedition refresh", () -> {
                     String runNow = com.fortuneandfavors.economy.ExpeditionManager.activeExpeditionName(player.getUUID());
                     String runPrev = playerExpeditionNames.put(player.getUUID(), runNow);
                     if (!java.util.Objects.equals(runPrev, runNow)) {
                        com.fortuneandfavors.economy.TagManager.refreshTabList(server);
                     }
                  });
                  // Walking into another dimension is the moment a mod can quietly
                  // take an inventory with it, so the pre-travel state is saved
                  // before anything in the new dimension can touch it.
                  Safe.run(
                     "inventory snapshot on dimension change",
                     () -> com.fortuneandfavors.util.LastInventoryHolder.noteDimension(player, player.level().dimension())
                  );
                  Safe.run("playtime tick", () -> com.fortuneandfavors.economy.PlaytimeManager.tick(player));
                  // Death/Bounty compasses only act while actually held. Scan
                  // the two hand slots instead of every tick handler re-reading
                  // custom NBT for the full inventory (identical behavior - the
                  // compass works from either hand only).
                  boolean holdingCompass = isHoldingModCompass(player);
                  if (perfOn || holdingCompass) {
                     if (perfOn) {
                        // Keep the name present in /ff perf even when skipped.
                        PerfMonitor.record("death compass tick", 0L);
                        PerfMonitor.record("bounty compass tick", 0L);
                     }
                     if (holdingCompass) {
                        Safe.run("death compass tick", () -> com.fortuneandfavors.economy.DeathCompassManager.tick(player));
                        Safe.run("bounty compass tick", () -> com.fortuneandfavors.economy.BountyCompassManager.tick(player));
                     }
                  }
                  // Legacy reapply only matters for swords/axes carrying Legacy
                  // (the enchant's attribute bearer). Gate on material tag first
                  // - a pure registry check - so bows, picks, compasses and
                  // every other main-hand item skip the handler (and its custom
                  // NBT tag copy) entirely, 20x/s per player.
                  ItemStack legacyHand = player.getMainHandItem();
                  if (!legacyHand.isEmpty()
                     && (legacyHand.is(net.minecraft.tags.ItemTags.SWORDS) || legacyHand.is(net.minecraft.tags.ItemTags.AXES))) {
                     Safe.run("legacy weapon reapply", () -> com.fortuneandfavors.economy.CustomEnchantments.reapplyLegacyModifiers(player.getMainHandItem()));
                  }
                  Safe.run("elevator tick", () -> ElevatorBlock.tickPlayer(player));
                  Safe.run("chair seat tick", () -> com.fortuneandfavors.block.ChairBlock.tickPlayer(player));
                  // A backpack with a furnace or campfire fused into it cooks out of the pack on its
                  // own clock - one item every twenty (or thirty) ticks, while it is being carried.
                  Safe.run("backpack kitchen", () -> com.fortuneandfavors.economy.BackpackKitchen.tick(player));
                  // The Potion Belt's brewing bay, while its window is open. An item has no block
                  // entity to tick it, which is exactly why the belt brews where it is carried -
                  // and why this line is what makes that true.
                  Safe.run("potion belt brew", () -> com.fortuneandfavors.menu.PotionBeltMenu.tickOpen(player));
                  Safe.run("advanced enchantment player tick", () -> AdvancedEnchantments.tickPlayer(player));
                  Safe.run("skill tick", () -> skillTick(player));
                  Safe.run("player feats tick", () -> com.fortuneandfavors.economy.PlayerFeatTracker.tick(player));
                  Safe.run("slime boot charge", () -> CombatGear.tickBoots(player));
                  Safe.run("cloak sword tick", () -> CombatGear.tickCloakSword(player));
                  // Mask/shroud/sculk are rare cosmetic gear: gate them on a
                  // piece actually being worn so every other player skips three
                  // AABB entity scans per tick.
                  boolean wearingSpecialGear = isWearingSpecialGear(player);
                  if (wearingSpecialGear || perfOn) {
                     if (perfOn) {
                        PerfMonitor.record("possessed mask tick", 0L);
                        PerfMonitor.record("mindbinder shroud tick", 0L);
                        PerfMonitor.record("sculk sensor tick", 0L);
                     }
                     if (wearingSpecialGear) {
                        Safe.run("possessed mask tick", () -> possessedMaskTick(player));
                        Safe.run("mindbinder shroud tick", () -> mindbinderShroudTick(player));
                     }
                  }
                  // Outside the gate on purpose: taking the leggings off is when the glow has to be cleared.
                  Safe.run("sculk sensor tick", () -> BossManager.sculkSensorTick(player));
                  Safe.run("elytra lunge tick", () -> ElytraLunge.tick(player));
                  Safe.run("sonic boom tick", () -> SonicBoom.tick(player));
                  Safe.run("backpack jukebox", () -> com.fortuneandfavors.economy.BackpackJukebox.tick(player));
                  Safe.run("afk tick", () -> {
                     if (com.fortuneandfavors.economy.AfkManager.tick(player)) {
                        com.fortuneandfavors.economy.TagManager.refreshTabList(server);
                     }
                  });
                  Safe.run("golden apple nerf", () -> goldenAppleNerfTick(player));
                  Safe.run("wormhole cooldown", () -> WormholeManager.tickCooldownDisplay(player));
                  Safe.run(
                     "nicekeepinv track feet",
                     () -> {
                        if (NiceKeepInventoryManager.isEnabled()
                           && player.isAlive()
                           && player.onGround()
                           && player.level().getGameTime() % 5L == 0L
                           && !player.isInWater()
                           && !player.isInLava()) {
                           BlockPos below = player.blockPosition().below();
                           BlockState bs = player.level().getBlockState(below);
                           if (!bs.isAir() && !bs.liquid() && bs.getFluidState().isEmpty()) {
                              NiceKeepInventoryManager.setLastTouched(player, below);
                           }
                        }
                     }
                  );
               }
            }
         );
   }

   /** Perf gate: are either of the mod's tracking compasses actually held?
    *  Lets the per-player loop skip both compass ticks (and their custom-data
    *  NBT reads) for every player not holding one. */
   private static boolean isHoldingModCompass(ServerPlayer player) {
      try {
         ItemStack main = player.getMainHandItem();
         ItemStack off = player.getOffhandItem();
         return ModItems.isDeathCompass(main)
            || ModItems.isDeathCompass(off)
            || ModItems.isBountyCompass(main)
            || ModItems.isBountyCompass(off);
      } catch (Throwable t) {
         // Fail open: run the compass ticks rather than skip them on a weird
         // stack state (these gates sit outside the Safe.run wrappers).
         return true;
      }
   }

   /** Perf gate: is any of the three special-armor tick systems (possessed
    *  mask, mindbinder shroud, sculk sensor leggings) actually equipped? Each
    *  of those handlers scans nearby entities with an AABB query, so players
    *  in normal armor skip them entirely. */
   private static boolean isWearingSpecialGear(ServerPlayer player) {
      try {
         return ModItems.isPossessedMask(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD))
            || ModItems.isMindbinderShroud(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST))
            || ModItems.isSculkSensorLeggings(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS));
      } catch (Throwable t) {
         // Fail open, same reasoning as isHoldingModCompass.
         return true;
      }
   }

   private static LivingEntity findByUuidNear(ServerLevel level, UUID id, BlockPos pos, int range) {
      Iterator var4 = level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(range), x -> x.getUUID().equals(id)).iterator();
      return var4.hasNext() ? (LivingEntity)var4.next() : null;
   }

   private static void possessedMaskTick(ServerPlayer p) {
      try {
         ServerLevel level = p.level();
         Iterator<Entry<UUID, TextDisplay>> it = MASK_SENSE_LABELS.entrySet().iterator();

         while (it.hasNext()) {
            Entry<UUID, TextDisplay> e = it.next();
            TextDisplay lbl = e.getValue();
            if (lbl.isRemoved()) {
               it.remove();
            } else {
               LivingEntity mob = findByUuidNear(level, e.getKey(), lbl.blockPosition(), 40);
               if (mob == null || !mob.hasEffect(MobEffects.GLOWING)) {
                  lbl.remove(RemovalReason.DISCARDED);
                  it.remove();
               }
            }
         }

         if (!ModItems.isPossessedMask(p.getItemBySlot(EquipmentSlot.HEAD))) {
            return;
         }

         if (BossManager.mindbinderAlive()) {
            // The mindbinder's shroud suppresses mask sense. Any labels this
            // player's mask created before are now orphaned in this level -
            // sweep them before returning so they cannot linger.
            for (TextDisplay orphan : level.getEntitiesOfClass(
               TextDisplay.class, p.getBoundingBox().inflate(24),
               td -> td.entityTags().contains(ModItems.DISPLAY_TMP_TAG)
            )) {
               orphan.remove(RemovalReason.DISCARDED);
            }
            return;
         }

         for (Holder<MobEffect> e : List.of(
            MobEffects.NAUSEA, MobEffects.WEAKNESS, MobEffects.SLOWNESS, MobEffects.BLINDNESS, MobEffects.DARKNESS, MobEffects.MINING_FATIGUE
         )) {
            p.removeEffect(e);
         }

         int radius = 8 + ModItems.tierOf(p.getItemBySlot(EquipmentSlot.HEAD)) * 4;

         for (Monster m : level.getEntitiesOfClass(
            Monster.class, p.getBoundingBox().inflate(radius), mob -> mob.isAlive() && mob.distanceToSqr(p) <= radius * radius
         )) {
            if (!m.hasEffect(MobEffects.GLOWING)) {
               m.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
            }

            Item drop = MASK_SENSE_DROPS.get(m.getType());
            long value = drop == null ? 0L : BlockValues.valueOf(new ItemStack(drop));
            if (value > 0L) {
               TextDisplay lbl = MASK_SENSE_LABELS.get(m.getUUID());
               if (lbl == null || lbl.isRemoved()) {
                  // A label saved by a crash may still exist in the world; adopt
                  // an untracked orphan instead of stacking a duplicate on the
                  // same mob (labels tracked by another mob are left alone).
                  for (TextDisplay orphan : level.getEntitiesOfClass(
                     TextDisplay.class, m.getBoundingBox().inflate(2.0),
                     td -> td.entityTags().contains(ModItems.DISPLAY_TMP_TAG) && !isTrackedMaskLabel(td.getUUID())
                  )) {
                     lbl = orphan;
                     MASK_SENSE_LABELS.put(m.getUUID(), lbl);
                     break;
                  }
               }
               if (lbl == null || lbl.isRemoved()) {
                  lbl = (TextDisplay)EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
                  if (lbl == null) {
                     continue;
                  }

                  lbl.setBillboardConstraints(BillboardConstraints.CENTER);
                  lbl.setNoGravity(true);
                  lbl.setInvulnerable(true);
                  // Tagged so the orphan sweep can find and remove these if they
                  // ever outlive this in-memory map (crash, dim unload, etc.).
                  lbl.addTag(ModItems.DISPLAY_TAG);
                  lbl.addTag(ModItems.DISPLAY_TMP_TAG);
                  lbl.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.2F), new Quaternionf()));
                  level.addFreshEntity(lbl);
                  MASK_SENSE_LABELS.put(m.getUUID(), lbl);
               }

               lbl.setText(Component.literal("§a$" + value));
               lbl.setPos(m.getX(), m.getEyeY() + 0.6, m.getZ());
            }
         }
      } catch (Exception var10) {
      }
   }

   /** True when the given UUID belongs to a possessed-mask price label the
    *  live mask-sense system is currently tracking (the map is keyed by mob, so
    *  this scans the tracked label entities). Used by the orphan sweep in
    *  CombatGear so it never blinks an active label. */
   public static boolean isTrackedMaskLabel(UUID labelId) {
      for (TextDisplay lbl : MASK_SENSE_LABELS.values()) {
         if (labelId.equals(lbl.getUUID())) {
            return true;
         }
      }
      return false;
   }

   private static void mindbinderShroudTick(ServerPlayer p) {
      try {
         ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
         if (!ModItems.isMindbinderShroud(chest)) {
            return;
         }

         ServerLevel level = p.level();
         int tier = ModItems.tierOf(chest);
         double radius = 8 + tier * 2;

         for (Monster m : level.getEntitiesOfClass(
            Monster.class, p.getBoundingBox().inflate(radius), mob -> mob.isAlive() && mob.distanceToSqr(p) <= radius * radius
         )) {
            if (!m.hasEffect(MobEffects.WEAKNESS)) {
               m.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, tier - 1, false, false));
            }

            if (!m.hasEffect(MobEffects.SLOWNESS)) {
               m.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, false));
            }
         }
      } catch (Exception var8) {
      }
   }

   private static InteractionResult tryWornGearAbility(ServerPlayer sp, InteractionHand hand) {
      if (!sp.isShiftKeyDown()) {
         return InteractionResult.PASS;
      }

      ItemStack head = sp.getItemBySlot(EquipmentSlot.HEAD);
      if (ModItems.isPossessedMask(head)) {
         String err = BossManager.usePossessedMask(sp);
         if (err != null) {
            sp.sendSystemMessage(Component.literal("§c" + err), true);
            return InteractionResult.FAIL;
         } else {
            return InteractionResult.SUCCESS;
         }
      } else {
         ItemStack chest = sp.getItemBySlot(EquipmentSlot.CHEST);
         if (ModItems.isMindbinderShroud(chest)) {
            String err = BossManager.useMindbinderShroud(sp);
            if (err != null) {
               sp.sendSystemMessage(Component.literal("§c" + err), true);
               return InteractionResult.FAIL;
            } else {
               return InteractionResult.SUCCESS;
            }
         } else {
            UUID uuid = sp.getUUID();
            ItemStack chestArmor = sp.getItemBySlot(EquipmentSlot.CHEST);
            long now = sp.level() instanceof ServerLevel sl ? sl.getGameTime() : 0L;
            if (ModItems.isWarlordCloak(chestArmor)) {
               long ready = warlordRageCooldown.getOrDefault(uuid, 0L);
               if (now < ready) {
                  Chat.msg(sp, "&cWarlord's Rage is still recharging.");
                  return InteractionResult.FAIL;
               }
               sp.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 240, 1, false, true, true));
               sp.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 240, 0, false, true, true));
               AttributeInstance atkSpeed = sp.getAttribute(Attributes.ATTACK_SPEED);
               if (atkSpeed != null) {
                  atkSpeed.addTransientModifier(new AttributeModifier(FortuneFavorsMod.id("warlord_rage_speed"), 0.15, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
               }
               warlordRageCooldown.put(uuid, now + 1200L); // 1 minute
               com.fortuneandfavors.net.FfVfx.particles(sp.level(), ParticleTypes.ANGRY_VILLAGER, sp.getX(), sp.getY() + 1.6, sp.getZ(), 25, 0.8, 0.8, 0.8, 0.05);
               sp.level().playSound(null, sp.blockPosition(), SoundEvents.WITHER_AMBIENT, SoundSource.PLAYERS, 1.0F, 1.4F);
               Chat.msg(sp, "&c&lWARLORD'S RAGE! &7Strength II, Resistance I and +15% attack speed for 12s.");
               return InteractionResult.SUCCESS;
            }
            if (ModItems.isEvokerCloak(chestArmor)) {
               long ready = evokerVitalityCooldown.getOrDefault(uuid, 0L);
               if (now < ready) {
                  Chat.msg(sp, "&cThe totem of vitality is still recharging.");
                  return InteractionResult.FAIL;
               }
               sp.heal(120.0F);
               sp.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, 200, 0, false, true, true));
               RaidGearManager.summonCloakVex(sp);
               evokerVitalityCooldown.put(uuid, now + 900L); // 45 seconds
               com.fortuneandfavors.net.FfVfx.particles(sp.level(), ParticleTypes.TOTEM_OF_UNDYING, sp.getX(), sp.getY() + 1.0, sp.getZ(), 24, 0.5, 0.6, 0.5, 0.1);
               sp.level().playSound(null, sp.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
               Chat.msg(sp, "&5Totem of Vitality: &f120 HP&7 healed, +2 hearts max for 10s, an extra vex joins you.");
               return InteractionResult.SUCCESS;
            }
            if (ModItems.isIllusionerCloak(chestArmor)) {
               long ready = illusionDashCooldown.getOrDefault(uuid, 0L);
               if (now < ready) {
                  Chat.msg(sp, "&cYour fade is still recharging.");
                  return InteractionResult.FAIL;
               }
               Vec3 look = sp.getLookAngle();
               sp.setDeltaMovement(sp.getDeltaMovement().add(look.x * 1.8, 0.1, look.z * 1.8));
               sp.hurtMarked = true;
               sp.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 30, 0, false, true, true));
               sp.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 2, false, true, true));
               illusionDashCooldown.put(uuid, now + 400L); // 20 seconds
               com.fortuneandfavors.net.FfVfx.particles(sp.level(), ParticleTypes.END_ROD, sp.getX(), sp.getY() + 1.0, sp.getZ(), 20, 0.4, 0.5, 0.4, 0.05);
               sp.level().playSound(null, sp.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.9F, 1.3F);
               Chat.msg(sp, "&9Illusion dash - &fvanish!&7");
               return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
         }
      }
   }

   private static void skillTick(ServerPlayer player) {
      if (ModConfig.is("skills")) {
         UUID uuid = player.getUUID();
         ItemStack held = player.getMainHandItem();
         int haste = SkillManager.miningHasteLevel(uuid);
         if (haste > 0 && SkillManager.isPickaxe(held) && !player.hasEffect(MobEffects.HASTE)) {
            player.addEffect(new MobEffectInstance(MobEffects.HASTE, 60, haste - 1, false, false));
         }

         if (SkillManager.caveSight(uuid) && SkillManager.isPickaxe(held) && player.getY() < 0.0 && !player.hasEffect(MobEffects.NIGHT_VISION)) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 120, 0, false, false));
         }

         if (SkillManager.farmerVigor(uuid) && SkillManager.isHoe(held) && !player.hasEffect(MobEffects.SPEED)) {
            player.addEffect(new MobEffectInstance(MobEffects.SPEED, 60, 0, false, false));
         }

         // Deep Sea, the fishing tree's one passive: the rod has to be in hand and its line in the
         // water, so it is a fishing upgrade and not a swim-anywhere upgrade. Both effects are on
         // the same short clock the rest of these use, which is what makes them stop the moment the
         // rod does.
         if (SkillManager.deepSea(uuid) && SkillManager.isFishingRod(held) && player.isInWater()) {
            if (!player.hasEffect(MobEffects.WATER_BREATHING)) {
               player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 60, 0, false, false));
            }

            if (!player.hasEffect(MobEffects.DOLPHINS_GRACE)) {
               player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 60, 0, false, false));
            }
         }

         ItemStack feet = player.getItemBySlot(EquipmentSlot.FEET);
         if (ModItems.isSlimeBoots(feet)) {
            int jumpLevel = ModItems.slimeBootsJumpLevel(feet);
            if (jumpLevel <= 0) {
               if (player.hasEffect(MobEffects.JUMP_BOOST)) {
                  player.removeEffect(MobEffects.JUMP_BOOST);
               }
            } else if (!player.hasEffect(MobEffects.JUMP_BOOST)) {
               player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, 60, jumpLevel - 1, false, false));
            }
         }

         ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
         boolean crown = ModItems.isWitherCrown(head);
         int crownTier = crown ? ModItems.tierOf(head) : 0;
         // Crown regen-strip converts wither into healing - but NOT inside a
         // live domain expansion: the Ascended Wither's wither is by design
         // unstrippable there, so crown wearers get withered like everyone else.
         if (crown && player.hasEffect(MobEffects.WITHER) && !WitherReworkManager.inDomain(player)) {
            player.removeEffect(MobEffects.WITHER);
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 120, crownTier - 1, false, false));
         }

         if (crown && crownTier >= 3 && player.getHealth() < player.getMaxHealth()) {
            long now = player.level().getGameTime();
            long next = crownRegenCooldown.getOrDefault(uuid, 0L);
            if (now >= next) {
               player.heal(1.0F);
               crownRegenCooldown.put(uuid, now + 120L);
               com.fortuneandfavors.net.FfVfx.particles(player.level(), ParticleTypes.SOUL_FIRE_FLAME, player.getX(), player.getY() + 1.4, player.getZ(), 3, 0.3, 0.4, 0.3, 0.01);
            }
         }

         ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
         boolean stoneheart = ModItems.isStoneheart(chest);
         AttributeInstance kbRes = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
         Identifier kbId = FortuneFavorsMod.id("stoneheart_kb");
         boolean standing = stoneheart && player.getDeltaMovement().horizontalDistanceSqr() < 0.002;
         long shNow = player.level().getGameTime();
         if (standing) {
            // Stoneheart still makes you immovable while standing, but the damage
            // resistance only kicks in after holding your ground for 1 second.
            stoneheartStandingSince.putIfAbsent(uuid, shNow);
            if (kbRes != null && !kbRes.hasModifier(kbId)) {
               kbRes.addTransientModifier(new AttributeModifier(kbId, 1.0, Operation.ADD_VALUE));
            }

            if (shNow - stoneheartStandingSince.get(uuid) >= 20L && !player.hasEffect(MobEffects.RESISTANCE)) {
               player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 2, 0, false, false));
            }
         } else {
            stoneheartStandingSince.remove(uuid);
            if (kbRes != null && kbRes.hasModifier(kbId)) {
               kbRes.removeModifier(kbId);
            }

            player.removeEffect(MobEffects.RESISTANCE);
         }

         if ((player.level().getGameTime() & 7L) == 0L && player.level() instanceof ServerLevel sl) {
            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();
            if (crown) {
               for (int i = 0; i < 3; i++) {
                  double a = (sl.getGameTime() * 0.2 + i * (Math.PI * 2.0 / 3.0)) % (Math.PI * 2);
                  com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.SOUL_FIRE_FLAME, x + Math.cos(a) * 0.4, y + 1.9, z + Math.sin(a) * 0.4, 1, 0.0, 0.0, 0.0, 0.0);
               }
            }

            if (crown || ModItems.isWitherStaff(held) || ModItems.isWitherBlade(held)) {
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ENCHANT, x, y + 1.3, z, 2, 0.35, 0.5, 0.35, 0.05);
            }

            if (ModItems.isWitherStaff(held)) {
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.SOUL_FIRE_FLAME, x, y + 1.3, z, 2, 0.3, 0.4, 0.3, 0.02);
            }

            if (ModItems.isRaidBossToken(held)) {
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.SOUL_FIRE_FLAME, x, y + 1.5, z, 2, 0.3, 0.4, 0.3, 0.02);
            }
         }
      }
   }

   /**
    * The one place a guild's PvP and PvE score is earned.
    *
    * <p>Both halves had a hole in them, and they are the same hole seen twice: the
    * score was only ever asked of a blow some player was named on.
    *
    * <p><b>PvE.</b> Every raid boss fights to a ceremony. The mod catches the killing
    * blow, plays the death out and then ends the body with its own damage, so vanilla
    * records no attacker: the death arrives, the bosses drop their loot, and the score
    * asks "who killed this", gets nothing, and moves on. The body still remembers the
    * last player who hit it, and the last player to hit a boss is the one who killed it.
    *
    * <p><b>PvP.</b> A duel refuses the blow that would kill the loser - the bout ends
    * without a death - so a kill that happened in a duel never reached a death event at
    * all. Duels credit their own winner now (see {@code DuelManager.endDuel}), which is
    * why a death inside a live duel is left to the duel here.
    */
   private static void onScoreDeath(LivingEntity entity, DamageSource source) {
      if (entity.level().isClientSide()) {
         return;
      }

      ServerPlayer killer = attackerOf(source);
      if (killer == null) {
         killer = lastPlayerHitter(entity);
      }
      if (killer == null || entity == killer) {
         return;
      }

      if (entity instanceof ServerPlayer victim) {
         // A duel is credited by the duel, at the moment it is decided.
         if (DuelManager.isInDuel(victim.getUUID()) || GuildManager.isGuildmate(killer.getUUID(), victim.getUUID())) {
            return;
         }

         ServerPlayer pvpKiller = killer;
         ServerPlayer pvpVictim = victim;
         Safe.run(
            "guild pvp score",
            () -> GuildManager.addPvp(pvpKiller.getUUID(), GuildManager.atWar(pvpKiller.getUUID(), pvpVictim.getUUID()) ? 2L : 1L)
         );
         return;
      }

      if (BossManager.isBoss(entity) || entity.getType() == EntityTypes.WITHER || entity.getType() == EntityTypes.ENDER_DRAGON) {
         LivingEntity slain = entity;
         ServerPlayer credited = killer;
         Safe.run("guild pve score", () -> GuildManager.creditBossKill(credited.getUUID(), slain.getUUID()));
      }
   }

   /** The player a blow names as its attacker, if it names one at all. */
   private static ServerPlayer attackerOf(DamageSource source) {
      if (source.getEntity() instanceof ServerPlayer p) {
         return p;
      }
      return source.getDirectEntity() instanceof Projectile pr && pr.getOwner() instanceof ServerPlayer p ? p : null;
   }

   /**
    * The last player who hurt this body. A boss ended by its own ceremony is removed
    * from the world with the mod's damage, which names no attacker - but the body it
    * is removed from still knows whose hit landed last, and that is who killed it.
    */
   private static ServerPlayer lastPlayerHitter(LivingEntity entity) {
      return entity instanceof Mob mob && mob.getLastHurtByMob() instanceof ServerPlayer p ? p : null;
   }

   private static void onDeath(LivingEntity entity, DamageSource source) {
      if (!entity.level().isClientSide()) {
         ServerPlayer killer = attackerOf(source);

         if (entity instanceof ServerPlayer dp) {
            ServerPlayer finalKiller = killer;
            Safe.run("player feats death", () -> com.fortuneandfavors.economy.PlayerFeatTracker.onPlayerDeath(dp, source));
            Safe.run("duel death", () -> DuelManager.onDeath(dp, finalKiller));
            Safe.run("expedition death", () -> ExpeditionManager.onDeath(dp));
            // Dying on a string is not the end of the fight - it is the next phase of
            // it. A threaded player who dies while the Puppeteer lives leaves their
            // puppet standing (see PuppeteerManager.onPlayerDeath).
            Safe.run(
               "puppeteer rebirth",
               () -> com.fortuneandfavors.economy.PuppeteerManager.onPlayerDeath(entity.level().getServer(), dp)
            );
         }

         // The Puppeteer's Mask: a kill while wearing it sometimes leaves a puppet of
         // the dead in the wearer's service.
         if (killer != null && entity instanceof net.minecraft.world.entity.LivingEntity maskVictim) {
            ServerPlayer maskKiller = killer;
            Safe.run("puppeteer mask kill", () -> com.fortuneandfavors.economy.PuppeteerGear.onKill(maskKiller, maskVictim));
         }

         // The four newest raid bosses pay their loot through a once-only ledger
         // (see ClockworkKingManager.onBossDeath). Their death ceremonies grant it,
         // and this is the safety net: a death that finds another route - an admin
         // /kill, a command, or vanilla's own body simply falling over - still pays
         // out instead of silently dropping nothing. The ledger makes the second
         // caller a no-op, so nothing ever drops twice.
         if (entity instanceof Mob deadBoss && entity.level() instanceof ServerLevel deadLevel) {
            Safe.run("clockwork king loot", () -> {
               if (com.fortuneandfavors.economy.ClockworkKingManager.isClockworkKing(deadBoss)) {
                  com.fortuneandfavors.economy.ClockworkKingManager.onBossDeath(deadLevel, deadBoss);
               }
            });
            Safe.run("starbound magister loot", () -> {
               if (com.fortuneandfavors.economy.StarboundMagisterManager.isMagister(deadBoss)) {
                  com.fortuneandfavors.economy.StarboundMagisterManager.onBossDeath(deadLevel, deadBoss);
               }
            });
            Safe.run("void shaper loot", () -> {
               if (com.fortuneandfavors.economy.VoidShaperManager.isVoidShaper(deadBoss)) {
                  com.fortuneandfavors.economy.VoidShaperManager.onBossDeath(deadLevel, deadBoss);
               }
            });
            Safe.run("emerald sovereign loot", () -> {
               if (com.fortuneandfavors.economy.EmeraldSovereignManager.isSovereign(deadBoss)) {
                  com.fortuneandfavors.economy.EmeraldSovereignManager.onBossDeath(deadLevel, deadBoss);
               }
            });
            // The Puppeteer pays through the same kind of once-only ledger, for the same
            // reason: his death is a ceremony, and a body that reached zero health by any
            // other route used to be torn down with nothing said and nothing dropped.
            Safe.run("puppeteer loot", () -> {
               if (com.fortuneandfavors.economy.PuppeteerManager.isPuppeteer(deadBoss)) {
                  com.fortuneandfavors.economy.PuppeteerManager.onBossDeath(deadBoss);
               }
            });
            // The sea and the sky, on the same ledger and for the same reason. Both cancel their
            // own killing blow and play a ceremony, so an admin's /kill, a command or a body that
            // simply fell over is the route that would otherwise drop nothing at all.
            Safe.run("drowned sovereign loot", () -> {
               if (com.fortuneandfavors.economy.DrownedSovereignManager.isDrownedSovereign(deadBoss)) {
                  com.fortuneandfavors.economy.DrownedSovereignManager.onBossDeath(deadLevel, deadBoss);
               }
            });
            Safe.run("gale warden loot", () -> {
               if (com.fortuneandfavors.economy.GaleWardenManager.isGaleWarden(deadBoss)) {
                  com.fortuneandfavors.economy.GaleWardenManager.onBossDeath(deadLevel, deadBoss);
               }
            });
         }

         if (entity instanceof ServerPlayer nkiPlayer) {
            // No snapshot here ON PURPOSE. This handler runs after vanilla has
            // emptied every slot, so capturing now would record the state after the
            // loss and overwrite the real one. The death snapshot is taken in the
            // death-drop hook instead, while the inventory is still intact.
            // Soulbind is retained by the death-drop hook when NKI is off;
            // NKI itself already classifies Soulbound items as important.
            Safe.run("nicekeepinv death", () -> NiceKeepInventoryManager.onPlayerDeath(nkiPlayer, entity.level().getServer()));
            Safe.run("wormhole death record", () -> WormholeManager.recordDeath(nkiPlayer));
         }

         // Prison mob kill rewards
         if (entity instanceof net.minecraft.world.entity.LivingEntity victim && killer instanceof ServerPlayer killerSp) {
            Safe.run("prison mob kill", () -> PrisonManager.onMobKill(killerSp, victim));
            Safe.run("expedition mob kill", () -> ExpeditionManager.onMobKill(killerSp, victim));
         }

         // Any pack body that stops existing gives its glow marker back, whoever killed it - and
         // whether or not anybody did. A mark is a scoreboard name, a name outlives the body that
         // held it, and the next natural spawn handed that name would walk around wearing a pack's
         // outline it was never part of. Gated on the dimension so the overworld pays nothing for it.
         if (entity instanceof net.minecraft.world.entity.LivingEntity markedBody
            && markedBody.level().dimension().equals(ExpeditionManager.EXPEDITION_DIM)) {
            Safe.run("expedition pack mark release", () -> ExpeditionManager.releaseMark(markedBody));
         }

         // A player-summoned vanilla wither is not one of BossManager's fights, so it never reached
         // the boss branch below at all: nothing completed a board's bounty when somebody finally
         // killed it, and the party's deaths could not be counted. Two hooks, one body.
         if (entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss) {
            ServerPlayer witherKiller = killer;
            Safe.run("dynamic wither slain", () -> DynamicContractsManager.onWitherSlain(entity.level().getServer(), witherKiller));
         }

         if (BossManager.isBoss(entity)) {
            String bossKey = BossManager.bossKey((LivingEntity)entity);
            BossManager.onKilled((ServerLevel)entity.level(), entity, killer);
            if (killer != null && bossKey != null) {
               StreakTrackerManager.onBossKill(killer);
               BossCodexManager.onBossKilled(entity.level().getServer(), bossKey, killer, 0L);
               FirstEverRecordManager.onBossKilled(entity.level().getServer(), bossKey, killer);
               com.fortuneandfavors.economy.ServerNewspaperManager.logEvent(
                  entity.level().getServer(),
                  "The " + com.fortuneandfavors.economy.BossCodexManager.displayName(bossKey) + " was slain by " + killer.getName().getString() + "!"
               );
               DynamicContractsManager.onBossKilled(entity.level().getServer(), bossKey, killer);
               DailyWeeklyChallengeManager.onMobKill(killer, true);
               CooperativeAchievementManager.onBossKilled(entity.level().getServer(), bossKey, killer, 1, false, 0L);
               LeaderboardManager.onBossKill(killer);
            }

         if (entity instanceof LivingEntity le) {
               ServerPlayer runeKiller = killer;
               if (killer != null && !(entity instanceof ServerPlayer)) {
                  Safe.run("rune fortune", () -> RuneManager.onKill(runeKiller, le));
               }
               Safe.run("raid warlord", () -> PlayerRaidManager.onEntityDeath(le, runeKiller));
            }
         }

         if (killer != null && BossManager.isGuard(entity)) {
            BossManager.onGuardKilled((ServerLevel)entity.level(), entity, killer);
         }

         if (entity instanceof ServerPlayer victim) {
            BossManager.onPlayerDeath((ServerLevel)entity.level(), victim);
            Safe.run("scarlet devil player death", () -> com.fortuneandfavors.economy.ScarletDevilManager.onPlayerDeath(victim));
            // A player bled dry by the Prism dies for real, but owes nothing: the
            // inventory copied when the ritual began is written straight back.
            Safe.run("blood ritual safe death", () -> com.fortuneandfavors.economy.ScarletGear.onPlayerDeath(victim));
            Safe.run("raid betrayer death", () -> PlayerRaidManager.onPlayerDeath(victim));
            StreakTrackerManager.onPlayerDeath(victim);
            // Read off the damage source, not the killer: a mob is not a ServerPlayer, so a wither
            // that kills somebody arrives here with `killer` already null.
            if (source.getEntity() instanceof net.minecraft.world.entity.boss.wither.WitherBoss) {
               Safe.run("dynamic wither kill", () -> DynamicContractsManager.onWitherKilledPlayer(entity.level().getServer(), victim));
            }
            // The world's own reflex, and the quietest thing in this handler: a player beaten down by
            // a zombie's own hand gets up again somewhere that is not where they fell, because
            // another one is already standing over the spot wearing one piece of what they had on.
            // No line is printed, on purpose - see ReactiveZombie.
            Safe.run(
               "reactive zombie",
               () -> com.fortuneandfavors.economy.ReactiveZombie.onPlayerDeath(victim, source)
            );
            // A teammate down with the party still standing. Read off the damage source, like the
            // wither above: a mob is not a ServerPlayer, so `killer` is null for every mob death
            // and the body that did it is only visible here.
            Safe.run("dynamic downed teammate", () -> DynamicContractsManager.onPlayerDowned(victim, source.getEntity()));
            if (killer != null && BossManager.isBoss(killer)) {
               String bk = BossManager.bossKey(killer);
               if (bk != null) {
                  BossCodexManager.onPlayerKilledByBoss(entity.level().getServer(), bk, victim);
                  DynamicContractsManager.onPlayerKilledByBoss(entity.level().getServer(), bk, victim);
               }
            }
            if (killer != null && killer != victim) {
               ServerPlayer k = killer;
               ServerPlayer v = victim;
               DailyWeeklyChallengeManager.onPlayerKill(k);
               Safe.run("dynamic hunt tracking", () -> DynamicContractsManager.onPlayerKilledByPlayer(entity.level().getServer(), k, v));
               Safe.run("dynamic hunt target died", () -> DynamicContractsManager.onHuntTargetDied(entity.level().getServer(), v, k));
            }
         }

         if (killer != null && BossManager.isIllusion(entity)) {
            BossManager.onIllusionKilled((ServerLevel)entity.level(), entity, killer);
         }

         if (BossManager.isBloodRevenant(entity)) {
            BossManager.onRevenantKilled((ServerLevel)entity.level(), entity, killer);
         }

         // Tearing a machine off the Clockwork King is the whole point of his fight,
         // so the kill has to be reported: he loses a tier of armour, a slice of real
         // health, and the window that follows is the only time his health bar really
         // moves. Handled here rather than in his tick so a machine killed by another
         // player, or by anything other than the King, still counts.
         if (entity instanceof Mob machineMob && com.fortuneandfavors.economy.ClockworkKingManager.isOwnedMachine(machineMob)) {
            com.fortuneandfavors.economy.ClockworkKingManager.onMachineKilled((ServerLevel)entity.level(), machineMob, killer);
         }

         if (entity instanceof Mob bloomMob && BossManager.isSculkWrapped(bloomMob) && killer != null) {
            BossManager.onSculkBloomKill((ServerLevel)entity.level(), bloomMob, killer);
         }

         if (killer != null) {
            if (entity instanceof ServerPlayer victim) {
               if (victim != killer) {
                  BountyManager.onKilled(((ServerLevel)entity.level()).getServer(), victim, killer);
                  if (killer != null) {
                     BossManager.onCorruptPlayerKill((ServerLevel)entity.level(), killer, victim);
                  }
               }
            } else {
               JobManager.onKill(killer, entity);
               SkillManager.onKill(killer, entity.getType());
               CombatGear.onKill(killer);
               // The Emerald Seal mints an emerald from the occasional hostile kill.
               if (entity instanceof net.minecraft.world.entity.LivingEntity sealVictim) {
                  ServerPlayer sealKiller = killer;
                  Safe.run("emerald seal kill", () -> com.fortuneandfavors.economy.SovereignGear.onHostileKill(sealKiller, sealVictim));
               }
               if (!BossManager.isBoss(entity)) {
                  ServerPlayer k = killer;
                  DailyWeeklyChallengeManager.onMobKill(k, false);
                  // The body is passed as well as the killer: a bounty names one mob, and the
                  // killer alone cannot say which one died.
                  Safe.run("dynamic mob job", () -> DynamicContractsManager.onMobKilled(k, entity));
               }
            }
         }
      }
   }

   /**
    * Test-only: run the real right-click dispatch for {@code stack} on a headless
    * player, exactly as the game's use hook would, and report what it returned.
    *
    * <p>The player's own hand is put back in the {@code finally}, so probing an
    * item can never consume, swap or re-forge something a real player is holding.
    * Only {@link SelfTest} calls this; nothing in play does.
    */
   public static InteractionResult probeUse(ServerPlayer player, ItemStack stack) {
      ItemStack before = player.getItemInHand(InteractionHand.MAIN_HAND);
      player.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
      try {
         return onUseItem(player, player.level(), InteractionHand.MAIN_HAND);
      } finally {
         player.setItemInHand(InteractionHand.MAIN_HAND, before);
      }
   }

   /**
    * Test-only: the right-click path with an <em>empty</em> hand, which is how a
    * worn-gear ability is triggered without also holding the piece. Same contract
    * as {@link #probeUse}.
    */
   public static InteractionResult probeUseEmptyHand(ServerPlayer player) {
      ItemStack before = player.getItemInHand(InteractionHand.MAIN_HAND);
      player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
      try {
         return onUseItem(player, player.level(), InteractionHand.MAIN_HAND);
      } finally {
         player.setItemInHand(InteractionHand.MAIN_HAND, before);
      }
   }

   /**
    * Test-only: the right-click-a-<em>block</em> path with an empty hand, which is
    * how the worn-gear abilities fire ({@link #tryWornGearAbility}). Same contract
    * as {@link #probeUse}: the hand is restored afterwards, and only
    * {@link SelfTest} calls it.
    */
   public static InteractionResult probeUseOnBlock(ServerPlayer player, BlockHitResult hit) {
      ItemStack before = player.getItemInHand(InteractionHand.MAIN_HAND);
      player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
      try {
         return onUseBlock(player, player.level(), InteractionHand.MAIN_HAND, hit);
      } finally {
         player.setItemInHand(InteractionHand.MAIN_HAND, before);
      }
   }

   /**
    * Test-only: the right-click-a-block path with a specific item in hand and a
    * specific sneak state, so a check can do exactly what a player does when
    * placing a chest-shop sign. Sneak state and hand are both restored in the
    * {@code finally}; only {@link SelfTest} calls this.
    */
   public static InteractionResult probeUseOnBlockWith(ServerPlayer player, ItemStack stack, BlockHitResult hit, boolean sneak) {
      ItemStack before = player.getItemInHand(InteractionHand.MAIN_HAND);
      boolean wasSneaking = player.isShiftKeyDown();
      player.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
      player.setShiftKeyDown(sneak);
      try {
         return onUseBlock(player, player.level(), InteractionHand.MAIN_HAND, hit);
      } finally {
         player.setItemInHand(InteractionHand.MAIN_HAND, before);
         player.setShiftKeyDown(wasSneaking);
      }
   }

   private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
      // A worn body cannot eat, drink, throw or place: see the attack gate above, which
      // this is the other half of. Every item that would let somebody end the possession
      // early - a totem, a pearl, a gapple - comes through here.
      if (!level.isClientSide()
         && player instanceof ServerPlayer worn
         && com.fortuneandfavors.economy.PuppeteerManager.isPossessed(worn)) {
         worn.sendOverlayMessage(
            net.minecraft.network.chat.Component.literal(
               "\u00a75\u2726 \u00a7fYour hand is not your own \u00a78- \u00a7dkill him to get it back."
            )
         );
         return InteractionResult.FAIL;
      }
      if (!level.isClientSide() && player instanceof ServerPlayer sp) {
         // A stopped player cannot act - that is the whole point of the Time
         // Lord's time stop and of a Pocket-Watch catching you.
         if (com.fortuneandfavors.economy.TimeLordManager.isTimeStopped(sp)) {
            return InteractionResult.FAIL;
         }
         ItemStack held = player.getItemInHand(hand);
         // The moderation kit. Every item in it is an action rather than an item: the click is
         // swallowed here so nothing downstream treats a barrier as a placeable block or the
         // sword as a swing.
         if (com.fortuneandfavors.anticheat.SpectateKit.isKitItem(held)
            && com.fortuneandfavors.anticheat.SpectateKit.use(sp, held)) {
            return InteractionResult.SUCCESS;
         }
         // Lunge Spear: right-click with the spear (elytra on) to LUNGE - the
         // old empty-hand and double-jump triggers are gone, on purpose.
         if (ElytraLunge.holdsSpear(sp) && ElytraLunge.tryLunge(sp)) {
            return InteractionResult.SUCCESS;
         }
         // A shop sign clicked on nothing: say what it is for. Without this the
         // click did literally nothing, which reads as a broken item.
         if (ModItems.isBuySign(held) || ModItems.isSellSign(held)) {
            Chat.msg(sp, "&7Right-click (or sneak-right-click) a chest to place this shop sign on it.");
            return InteractionResult.SUCCESS;
         }
         // The Potion Belt: the click is the item. Four flasks, drunk on a click inside the belt,
         // and brewed back full in the belt's own bay - a bottle, an ingredient and blaze powder,
         // through the game's own brewing recipes. It is not placeable, and it is not meant to be:
         // the belt is a brewing stand you carry, and the world already has the other kind.
         if (ModItems.isPotionBelt(held)) {
            com.fortuneandfavors.menu.PotionBeltMenu.open(sp, held);
            return InteractionResult.SUCCESS;
         }
         // The Expedition Compass: a fatal blow in a dungeon costs time as well as loot, and this is
         // the only thing that buys the time back. Spent only when there is a wait to erase.
         if (ModItems.isExpeditionCompass(held)) {
            String compassErr = com.fortuneandfavors.economy.ExpeditionManager.useExpeditionCompass(sp, held);
            if (compassErr != null) {
               Chat.msg(sp, "&c" + compassErr);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         // The Descent Ladder, dropped by an expedition floor guardian: right-click and
         // the shaft skips the next five chambers of the maze. A renamed vanilla ladder,
         // so the click is swallowed here before anything treats it as a placeable block.
         if (com.fortuneandfavors.economy.ExpeditionManager.isDescentLadder(held)) {
            String ladderErr = com.fortuneandfavors.economy.ExpeditionManager.useDescentLadder(sp, held);
            if (ladderErr != null) {
               Chat.msg(sp, "&c" + ladderErr);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         // A Time Shard from the supplier: the clock moves the only way that helps.
         if (com.fortuneandfavors.economy.ExpeditionManager.isTimeShard(held)) {
            String shardErr = com.fortuneandfavors.economy.ExpeditionManager.useTimeShard(sp, held);
            if (shardErr != null) {
               Chat.msg(sp, "&c" + shardErr);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         // The Loot Backpack: right-click opens it wherever you are standing. It is never
         // placeable - the bundle it looks like is only the shape - so the click is swallowed here
         // rather than reaching anything that would try to use it as a container item.
         if (com.fortuneandfavors.economy.LootBackpack.is(held)) {
            // A pack is only worth holding while the run it was handed out by is the run this body is
            // standing in. Anything else - one kept through a logout, one parked in a chest and
            // fetched afterwards, one left over from a friend's finished run - is spent, and this is
            // the door it would otherwise be opened through. See LootBackpack#belongsTo.
            if (!com.fortuneandfavors.economy.LootBackpack.belongsTo(
               held,
               com.fortuneandfavors.economy.ExpeditionManager.liveRunId(sp.getUUID())
            )) {
               com.fortuneandfavors.economy.LootBackpack.reclaim(
                  sp,
                  com.fortuneandfavors.economy.ExpeditionManager.liveRunId(sp.getUUID())
               );
               Chat.msg(sp, "&cThat pack was handed out by a run that is over, so the site has taken it back.");
               return InteractionResult.SUCCESS;
            }
            if (com.fortuneandfavors.economy.ExpeditionManager.isInExpedition(sp.getUUID())) {
               com.fortuneandfavors.menu.LootBackpackMenu.open(sp);
            } else {
               Chat.msg(sp, "&7Your Loot Backpack is empty of purpose outside a site - it is handed back when you leave.");
            }
            return InteractionResult.SUCCESS;
         }
         // A pack upgrader in hand: right-clicking it patches the pack the player is carrying, so a
         // patch that reaches them any other way than a chest window still does its job.
         if (com.fortuneandfavors.economy.LootBackpack.isUpgrader(held)) {
            String patchErr = com.fortuneandfavors.economy.ExpeditionManager.patchPack(sp);
            if (patchErr != null) {
               Chat.msg(sp, "&c" + patchErr);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         // Death Compass: right-click fires the sonar ping (hold to track).
         if (ModItems.isDeathCompass(held)) {
            com.fortuneandfavors.economy.DeathCompassManager.use(sp);
            return InteractionResult.SUCCESS;
         }
         // Bounty Compass: right-click announces the hunted player's position.
         if (ModItems.isBountyCompass(held)) {
            com.fortuneandfavors.economy.BountyCompassManager.use(sp);
            return InteractionResult.SUCCESS;
         }
         // ---- The Time Lord's kit: the rift clock and the three legendaries ----
         if (ModItems.isSpaceTimeRift(held)) {
            String riftErr = com.fortuneandfavors.economy.TimeLordManager.useSpaceTimeRift(sp, held);
            if (riftErr != null) {
               Chat.msg(sp, "&c" + riftErr);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isPocketWatch(held)) {
            String watchErr = com.fortuneandfavors.economy.TimeLordManager.usePocketWatch(sp, held);
            if (watchErr != null) {
               Chat.msg(sp, "&c" + watchErr);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isChronoShard(held)) {
            long shardNow = level.getGameTime();
            long shardLeft = ModItems.cooldownSecondsLeft(held, shardNow);
            if (shardLeft > 0L) {
               Chat.msg(sp, "&cThe Chrono Shard is still winding back (" + shardLeft + "s).");
               return InteractionResult.FAIL;
            }
            String shardErr = com.fortuneandfavors.economy.TimeLordManager.useChronoShard(sp);
            if (shardErr != null) {
               Chat.msg(sp, "&c" + shardErr);
               return InteractionResult.FAIL;
            }
            ModItems.setCooldownUntil(held, shardNow + 1200L);
            sp.getCooldowns().addCooldown(held, 1200);
            return InteractionResult.SUCCESS;
         }
         // The Distant Memory's greatsword: right-click lets go (the mirage's own rescue, on a
         // five-minute clock), sneak-right-click swings one remembered arc. Both are real
         // abilities rather than a stat line - see LastRemembrance for why the relic needed one
         // and where it refuses.
         if (ModItems.isLastRemembrance(held)) {
            String relicErr = com.fortuneandfavors.economy.LastRemembrance.use(sp, held);
            if (relicErr != null) {
               Chat.msg(sp, "&c" + relicErr);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isHourglass(held)) {
            long hgNow = level.getGameTime();
            long hgLeft = ModItems.cooldownSecondsLeft(held, hgNow);
            if (hgLeft > 0L) {
               Chat.msg(sp, "&cThe Hourglass is still emptying (" + hgLeft + "s).");
               return InteractionResult.FAIL;
            }
            String hgErr = com.fortuneandfavors.economy.TimeLordManager.useHourglass(sp);
            if (hgErr != null) {
               Chat.msg(sp, "&c" + hgErr);
               return InteractionResult.FAIL;
            }
            ModItems.setCooldownUntil(held, hgNow + 1800L);
            sp.getCooldowns().addCooldown(held, 1800);
            return InteractionResult.SUCCESS;
         }
         // ---- The Clockwork King: his winding key ----
         if (ModItems.isClockworkCore(held)) {
            String coreErr = com.fortuneandfavors.economy.ClockworkKingManager.useClockworkCore(sp, held);
            if (coreErr != null) {
               Chat.msg(sp, "&c" + coreErr);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         // ---- The Scarlet Devil: her blood vial ----
         if (ModItems.isScarletBlood(held)) {
            String bloodErr = com.fortuneandfavors.economy.ScarletDevilManager.useScarletBlood(sp, held);
            if (bloodErr != null) {
               Chat.msg(sp, "&c" + bloodErr);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         // Her two other legendaries: the book that casts her moves, and the
         // prism that leaves a servant behind.
         if (ModItems.isScarletGrimoire(held)) {
            String err = com.fortuneandfavors.economy.ScarletGear.useGrimoire(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isBloodPrism(held)) {
            String err = com.fortuneandfavors.economy.ScarletGear.usePrism(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         // ---- The three newest bosses: their summons, and their legendaries.
         if (ModItems.isAstralCompass(held)) {
            String err = com.fortuneandfavors.economy.StarboundMagisterManager.useAstralCompass(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isVoidAnchor(held)) {
            String err = com.fortuneandfavors.economy.VoidShaperManager.useVoidAnchor(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isSovereignsCrown(held)) {
            String err = com.fortuneandfavors.economy.EmeraldSovereignManager.useCrown(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isMagistersCodex(held)) {
            String err = com.fortuneandfavors.economy.MagisterGear.useCodex(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isShapingSigil(held)) {
            String err = com.fortuneandfavors.economy.VoidShaperGear.useSigil(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         // ---- The sea and the sky: their two summons, and their six legendaries.
         //
         // The Warden's Mantle is deliberately absent: a chestplate's right-click is how you put it
         // on, so all four of its abilities are read off the tick loop instead - see SeaAndSkyGear.
         // It is declared passive below rather than shipped as a dead click.
         if (ModItems.isSovereignsHeart(held)) {
            String err = com.fortuneandfavors.economy.DrownedSovereignManager.useSovereignsHeart(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isGaleSigil(held)) {
            String err = com.fortuneandfavors.economy.GaleWardenManager.useGaleSigil(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isLeviathansGrasp(held)) {
            String err = com.fortuneandfavors.economy.SeaAndSkyGear.useGrasp(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isTidecaller(held)) {
            String err = com.fortuneandfavors.economy.SeaAndSkyGear.useTidecaller(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isAbyssalChain(held)) {
            String err = com.fortuneandfavors.economy.SeaAndSkyGear.useChain(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isSkybreaker(held)) {
            String err = com.fortuneandfavors.economy.SeaAndSkyGear.useSkybreaker(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isGaleChakram(held)) {
            String err = com.fortuneandfavors.economy.SeaAndSkyGear.useChakram(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         // ---- The End's three: the rift blade, the star bow, the dragon's mace.
         // Each branch asks the *any-tier* predicate, because an awakened weapon has to keep
         // every behaviour its base form had - the upgrade changes the clock, not the weapon.
         if (ModItems.isAnyVoidfang(held)) {
            String err = com.fortuneandfavors.economy.EnderGear.useVoidfang(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isAnyStarfall(held)) {
            // A bow's right-click *is* how it is drawn, so this one cannot be claimed
            // outright: on a plain click the vanilla bow is handed the use back (PASS),
            // and the charged shot lives on the sneak click. Claiming the plain click
            // here would have made Starfall a bow that can never fire an arrow - which
            // is the whole weapon. A Bedrock client gets the ability on the plain click
            // instead, because that is where its click is, exactly as the Frostbound
            // Crown does it.
            if (!sp.isShiftKeyDown() && !ModPlatform.isBedrock(sp)) {
               return InteractionResult.PASS;
            }
            String err = com.fortuneandfavors.economy.EnderGear.useStarfall(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isAnyEnderheart(held)) {
            String err = com.fortuneandfavors.economy.EnderGear.useEnderheart(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         // ---- The Puppeteer's kit: the marionette, the strings, the masks ----
         if (ModItems.isWoodenMarionette(held)) {
            String err = com.fortuneandfavors.economy.PuppeteerManager.useWoodenMarionette(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isMarionetteStrings(held)) {
            com.fortuneandfavors.economy.PuppeteerGear.useStrings(sp, held);
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isRoyalContract(held)) {
            String err = com.fortuneandfavors.economy.SovereignGear.useContract(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isSovereignsBell(held)) {
            String err = com.fortuneandfavors.economy.SovereignGear.useBell(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         if (ModItems.isEmeraldSeal(held)) {
            String err = com.fortuneandfavors.economy.SovereignGear.useSeal(sp, held);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }
         // Sculk Fruit: eat it like food (full stack-safe, brief sculk sight).
         if (ModItems.isSculkFood(held) && sp.getFoodData().needsFood()) {
            com.fortuneandfavors.economy.SculkFoodManager.eat(sp, held);
            return InteractionResult.SUCCESS;
         }
         if (com.fortuneandfavors.economy.PrisonManager.isPrisonStar(held)) {
            com.fortuneandfavors.menu.PrisonMenu.open(sp);
            return InteractionResult.SUCCESS;
         }
         if (DuelManager.tryFeatherBoost(sp, held)) {
            return InteractionResult.SUCCESS;
         }

         if (DuelManager.tryOpenSkywarsKitMenu(sp, held)) {
            return InteractionResult.SUCCESS;
         }

         if (DuelManager.isSpectating(sp.getUUID()) && held.is(Items.NETHER_STAR)) {
            DuelManager.tryOpenSpectatorMenu(sp);
            return InteractionResult.SUCCESS;
         }

         if (DuelManager.isGoldenAppleHeadAllowed(sp) && DuelManager.isGoldenAppleHead(held) && DuelManager.eatGoldenAppleHead(sp, held)) {
            return InteractionResult.SUCCESS;
         }

         if (BossManager.isFullControlled(sp.getUUID())) {
            if (player.isShiftKeyDown() && ModItems.isPossessedMask(sp.getItemBySlot(EquipmentSlot.HEAD))) {
               String err = BossManager.usePossessedMask(sp);
               if (err != null) {
                  sp.sendSystemMessage(Component.literal("§c" + err), true);
                  return InteractionResult.FAIL;
               } else {
                  return InteractionResult.SUCCESS;
               }
            } else {
               if (sp.level().getGameTime() % 20L == 0L) {
                  sp.sendSystemMessage(Component.literal("§5Your hands are not your own - the Mindbinder holds the reins!"), true);
               }

               return InteractionResult.FAIL;
            }
         } else {
            ItemStack off = sp.getItemInHand(InteractionHand.OFF_HAND);
            // Runes socket in either orientation: item in main + rune in off,
            // OR rune in main + item in off. Right-clicking applies whichever
            // you're holding, so the natural "hold the rune and click" works.
            ItemStack runeTarget = null;
            ItemStack rune = null;
            if (RuneManager.isSocketable(held) && ModItems.isRune(off)) {
               runeTarget = held;
               rune = off;
            } else if (ModItems.isRune(held) && RuneManager.isSocketable(off)) {
               runeTarget = off;
               rune = held;
            }
            if (runeTarget != null) {
               String err = RuneManager.apply(sp, runeTarget, rune);
               if (err != null) {
                  Chat.msg(sp, "&c" + err);
                  return InteractionResult.FAIL;
               }
               // apply() no longer consumes; take exactly one rune now.
               if (!sp.getAbilities().instabuild) {
                  rune.shrink(1);
               }
               grantAllRunes(sp, runeTarget);
               return InteractionResult.SUCCESS;
            }

            // NOTE: the old offhand quick-fuse (hold a tome + right-click with the
            // weapon in your other hand) was removed - it hijacked right-clicks and
            // ate the weapon on failure. Tome fusion now happens ONLY through the
            // Item Forge and the anvil.

            if (ModItems.isSlimeBoots(held)) {
               if (sp.isShiftKeyDown()) {
                  boolean bounce = !ModItems.slimeBootsBounce(held);
                  ModItems.setSlimeBootsBounce(held, bounce);
                  sp.sendSystemMessage(
                     Component.literal("§aSlime Boots bounce: §f" + (bounce ? "ON - spring back on landing" : "OFF - land flat")), true
                  );
               } else {
                  int next = (ModItems.slimeBootsJumpLevel(held) + 1) % 4;
                  ModItems.setSlimeBootsJumpLevel(held, next);
                  sp.sendSystemMessage(Component.literal("§aSlime Boots jump boost: §f" + (next == 0 ? "Off" : "Level " + next)), true);
               }
               return InteractionResult.SUCCESS;
            }

            if (held.getItem() instanceof BucketItem bucket && !bucket.getContent().isSame(Fluids.EMPTY)) {
               Vec3 eye = sp.getEyePosition(1.0F);
               BlockHitResult hit = level.clip(
                  new ClipContext(eye, eye.add(sp.getLookAngle().scale(sp.blockInteractionRange())), Block.OUTLINE, Fluid.NONE, sp)
               );
               if (hit.getType() == Type.BLOCK) {
                  BlockPos click = hit.getBlockPos();
                  BlockPos target = click.relative(hit.getDirection());
                  if (level.getBlockState(click).getBlock() instanceof LiquidBlockContainer && bucket.getContent().isSame(Fluids.WATER)) {
                     target = click;
                  }

                  if (!ClaimManager.canBuild(sp, click) || !ClaimManager.canBuild(sp, target)) {
                     Chat.msg(sp, "&cThis land is claimed - you can't place fluids here.");
                     return InteractionResult.FAIL;
                  }
               }
            }

            if (ModItems.isRaidBanner(held)) {
               String err = PlayerRaidManager.triggerRaid(sp, held);
               if (err != null) {
                  Chat.msg(sp, "&c" + err);
                  return InteractionResult.FAIL;
               }
               return InteractionResult.SUCCESS;
            }

            // Mystery keys: right-clicking a key opens the Mystery Chest menu
            // directly (before this, keys only worked inside /menu).
            if (com.fortuneandfavors.economy.MysteryChestManager.isMysteryKey(held)) {
               com.fortuneandfavors.menu.MysteryChestMenu.open(sp);
               return InteractionResult.SUCCESS;
            }

            if (ModItems.isBackpack(held)) {
               ItemStack migrated = ModItems.migrateBackpack(held);
               if (migrated != held) {
                  // Write the upgraded stack back into the hand that was used.
                  // This used to target getSelectedSlot(), so right-clicking with
                  // a legacy backpack in the OFF hand overwrote whatever was in
                  // the selected hotbar slot: the old backpack vanished, the
                  // hotbar item was silently replaced and the client saw a
                  // ghost stack until the next inventory sync.
                  sp.setItemInHand(hand, migrated);
               }

               if (ModItems.isEnderBackpack(migrated) && sp.isShiftKeyDown()) {
                  SoundUtil.play(sp, ModSounds.BACKPACK);
                  sp.openMenu(
                     new SimpleMenuProvider(
                        (syncId, inv, p) -> ChestMenu.threeRows(syncId, inv, sp.getEnderChestInventory()),
                        Component.literal("§5§lEnder Chest")
                     )
                  );
               } else {
                  SoundUtil.play(sp, ModSounds.BACKPACK);
                  BackpackMenu.open(sp, migrated);
               }
               return InteractionResult.SUCCESS;
            } else if (ModItems.isBundle(held)) {
               SoundUtil.play(sp, ModSounds.BACKPACK);
               BundleMenu.open(sp, held);
               return InteractionResult.SUCCESS;
            } else if (ModItems.isSculkMedallion(held)) {
               if (!ModConfig.is("boss")) {
                  Chat.msg(sp, "&cBosses are disabled on this server.");
                  return InteractionResult.FAIL;
               }

               String err = BossManager.summonElderWarden(sp);
               if (err != null) {
                  Chat.msg(sp, "&c" + err);
                  return InteractionResult.FAIL;
               }

               if (!sp.getAbilities().instabuild) {
                  held.shrink(1);
               }

               return InteractionResult.SUCCESS;
            } else if (ModItems.isSculkMageStaff(held)) {
               if (!ModConfig.is("boss")) {
                  Chat.msg(sp, "&cBoss gear is disabled on this server.");
                  return InteractionResult.FAIL;
               } else if (player.isShiftKeyDown()) {
                  sp.sendSystemMessage(Component.literal(BossManager.toggleSculkStaffMode(sp)), true);
                  return InteractionResult.SUCCESS;
               } else if (sp.getCooldowns().isOnCooldown(held)) {
                  Chat.msg(sp, "&cThe staff's sculk is still recharging.");
                  return InteractionResult.FAIL;
               } else {
                  String err = BossManager.useSculkStaff(sp, held);
                  if (err != null) {
                     Chat.msg(sp, "&c" + err);
                     return InteractionResult.FAIL;
                  } else {
                     int tier = ModItems.tierOf(held);
                     sp.getCooldowns().addCooldown(held, (9 - (tier - 1) * 2) * 20);
                     return InteractionResult.SUCCESS;
                  }
               }
            } else if (ModItems.isSculkOrb(held)) {
               String err = BossManager.useSculkOrb(sp, held);
               if (err != null) {
                  Chat.msg(sp, "&c" + err);
                  return InteractionResult.SUCCESS;
               } else {
                  return InteractionResult.SUCCESS;
               }
            } else if (ModItems.isWardensCall(held)) {
               if (!ModConfig.is("boss")) {
                  Chat.msg(sp, "&cBoss gear is disabled on this server.");
                  return InteractionResult.FAIL;
               } else {
                  String err = BossManager.useWardensCall(sp);
                  if (err != null) {
                     Chat.msg(sp, "&c" + err);
                     return InteractionResult.FAIL;
                  } else {
                     sp.getCooldowns().addCooldown(held, 160);
                     return InteractionResult.SUCCESS;
                  }
               }
            } else {

               if (ModItems.isWormholePotion(held)) {
                  if (!ModConfig.is("exclusive")) {
                     Chat.msg(sp, "&cWormhole potions are disabled on this server.");
                     return InteractionResult.FAIL;
                  } else {
                     return (InteractionResult)(!WormholeManager.open(sp) ? InteractionResult.FAIL : InteractionResult.SUCCESS);
                  }
               } else if (ModItems.isMysteryBox(held)) {
                  if (!ModConfig.is("skills")) {
                     Chat.msg(sp, "&cMystery boxes are disabled on this server.");
                     return InteractionResult.FAIL;
                  } else {
                     MysteryBoxMenu.open(sp);
                     return InteractionResult.SUCCESS;
                  }
               } else if (ModItems.isRaidBossToken(held)) {
                  if (!ModConfig.is("boss")) {
                     Chat.msg(sp, "&cBosses are disabled on this server.");
                     return InteractionResult.FAIL;
                  }

                  String err = BossManager.spawnKing(sp);
                  if (err != null) {
                     Chat.msg(sp, "&c" + err);
                     return InteractionResult.FAIL;
                  }

                  if (!sp.getAbilities().instabuild) {
                     held.shrink(1);
                  }

                  return InteractionResult.SUCCESS;                } else if (!ModItems.isKingLootBox(held)
                   && !ModItems.isWitherLootBox(held)
                   && !ModItems.isSlimeLootBox(held)
                   && !ModItems.isGolemLootBox(held)
                   && !ModItems.isMindLootBox(held)
                   && !ModItems.isSnowLootBox(held)
                   && !ModItems.isSculkLootBox(held)
                   && !ModItems.isRaidLootBox(held)
                   && !ModItems.isTimeLordLootBox(held)
                   && !ModItems.isScarletLootBox(held)
                   && !ModItems.isClockworkLootBox(held)
                   && !ModItems.isStarboundLootBox(held)
                   && !ModItems.isVoidshaperLootBox(held)
                  && !ModItems.isPuppeteerLootBox(held)
                  // The two newest boxes belong in this list or the branch below never runs for
                  // them: the chain reads as "if it is not any of the boxes, do nothing", and a
                  // box missing from it is a box that silently does nothing when right-clicked.
                  && !ModItems.isDrownedLootBox(held)
                  && !ModItems.isGaleLootBox(held)
                  && !ModItems.isSovereignLootBox(held)) {
                  if (ModItems.isWitherStaff(held)) {
                     if (!ModConfig.is("boss")) {
                        Chat.msg(sp, "&cBoss gear is disabled on this server.");
                        return InteractionResult.FAIL;
                     }

                     if (sp.isShiftKeyDown() && ModItems.isMindAscended(held)) {
                        String dismissErr = BossManager.dismissFriendly(sp);
                        if (dismissErr != null) {
                           Chat.msg(sp, "&c" + dismissErr);
                           return InteractionResult.FAIL;
                        } else {
                           return InteractionResult.SUCCESS;
                        }
                     } else if (sp.getCooldowns().isOnCooldown(held)) {
                        Chat.msg(sp, "&cYour staff is still recharging.");
                        return InteractionResult.FAIL;
                     } else {
                        String err = BossManager.useStaff(sp);
                        if (err != null) {
                           Chat.msg(sp, "&c" + err);
                           return InteractionResult.FAIL;
                        } else {
                           int tier = ModItems.tierOf(held);
                           sp.getCooldowns().addCooldown(held, (30 - (tier - 1) * 10) * 20);
                           Chat.msg(sp, tier >= 3 ? "&5Your wither skeletons rise to fight beside you!" : "&5Your wither skeleton rises to fight beside you!");
                           return InteractionResult.SUCCESS;
                        }
                     }
                  } else if (ModItems.isSlimeBossToken(held)) {
                     if (!ModConfig.is("boss")) {
                        Chat.msg(sp, "&cBosses are disabled on this server.");
                        return InteractionResult.FAIL;
                     }

                     String err = BossManager.spawnSlimeKing(sp);
                     if (err != null) {
                        Chat.msg(sp, "&c" + err);
                        return InteractionResult.FAIL;
                     }

                     if (!sp.getAbilities().instabuild) {
                        held.shrink(1);
                     }

                     return InteractionResult.SUCCESS;
                  } else if (ModItems.isStoneGolemToken(held)) {
                     if (!ModConfig.is("boss")) {
                        Chat.msg(sp, "&cBosses are disabled on this server.");
                        return InteractionResult.FAIL;
                     }

                     String err = BossManager.throwBoulderBaby(sp);
                     if (err != null) {
                        Chat.msg(sp, "&c" + err);
                        return InteractionResult.FAIL;
                     }

                     if (!sp.getAbilities().instabuild) {
                        held.shrink(1);
                     }

                     return InteractionResult.SUCCESS;
                  } else if (ModItems.isSnowQueenToken(held)) {
                     if (!ModConfig.is("boss")) {
                        Chat.msg(sp, "&cBosses are disabled on this server.");
                        return InteractionResult.FAIL;
                     }

                     String err = BossManager.spawnSnowQueen(sp);
                     if (err != null) {
                        Chat.msg(sp, "&c" + err);
                        return InteractionResult.FAIL;
                     }

                     if (!sp.getAbilities().instabuild) {
                        held.shrink(1);
                     }

                     return InteractionResult.SUCCESS;
                  } else if (ModItems.isWitherCloakSword(held)) {
                     if (!ModConfig.is("boss")) {
                        Chat.msg(sp, "&cBoss gear is disabled on this server.");
                        return InteractionResult.FAIL;
                     } else if (sp.getCooldowns().isOnCooldown(held)) {
                        Chat.msg(sp, "&cThe cloak is still recharging.");
                        return InteractionResult.FAIL;
                     } else {
                        String err = BossManager.useWitherCloakSword(sp, held);
                        if (err != null) {
                           Chat.msg(sp, "&c" + err);
                           return InteractionResult.FAIL;
                        } else {
                           sp.getCooldowns().addCooldown(held, 300);
                           Chat.msg(sp, "&5The shroud folds in - it drinks the next hits and pours them back out.");
                           return InteractionResult.SUCCESS;
                        }
                     }
                  } else if (ModItems.isIceStaff(held)) {
                     if (!ModConfig.is("boss")) {
                        Chat.msg(sp, "&cBoss gear is disabled on this server.");
                        return InteractionResult.FAIL;
                     } else if (sp.getCooldowns().isOnCooldown(held)) {
                        Chat.msg(sp, "&cYour staff is still recharging.");
                        return InteractionResult.FAIL;
                     } else {
                        String err = BossManager.useIceStaff(sp, held);
                        if (err != null) {
                           Chat.msg(sp, "&c" + err);
                           return InteractionResult.FAIL;
                        } else {
                           return InteractionResult.SUCCESS;
                        }
                     }
                  } else if (ModItems.isFrostboundCrown(held)) {
                     if (!ModConfig.is("boss")) {
                        Chat.msg(sp, "&cBoss gear is disabled on this server.");
                        return InteractionResult.FAIL;
                     }

                     if (sp.getCooldowns().isOnCooldown(held)) {
                        Chat.msg(sp, "&cThe bow's frost is still gathering.");
                        return InteractionResult.FAIL;
                     }

                     if (ModPlatform.isBedrock(sp)) {
                        String err = BossManager.useFrostbow(sp, held, 1.0F);
                        if (err != null) {
                           Chat.msg(sp, "&c" + err);
                           return InteractionResult.FAIL;
                        } else {
                           return InteractionResult.SUCCESS;
                        }
                     } else {
                        return InteractionResult.PASS;
                     }
                  } else if (ModItems.isMindbinderEye(held)) {
                     if (!ModConfig.is("boss")) {
                        Chat.msg(sp, "&cBosses are disabled on this server.");
                        return InteractionResult.FAIL;
                     }

                     String err = BossManager.throwMindbinderEye(sp);
                     if (err != null) {
                        Chat.msg(sp, "&c" + err);
                        return InteractionResult.FAIL;
                     }

                     if (!sp.getAbilities().instabuild) {
                        held.shrink(1);
                     }

                     return InteractionResult.SUCCESS;
                  } else if (ModItems.isMindbinderStaff(held)) {
                     if (player.isShiftKeyDown()) {
                        String msg = BossManager.toggleMindStaffMode(sp);
                        sp.sendSystemMessage(Component.literal(msg), true);
                        return InteractionResult.SUCCESS;
                     }

                     if (sp.getCooldowns().isOnCooldown(held)) {
                        Chat.msg(sp, "&cThe staff's will is still recharging.");
                        return InteractionResult.FAIL;
                     }

                     int mode = BossManager.staffModeOf(sp);
                     if (mode == 1) {
                        String err = BossManager.useMindStaffHeal(sp);
                        if (err != null) {
                           Chat.msg(sp, "&c" + err);
                           return InteractionResult.FAIL;
                        }

                        int tier = ModItems.tierOf(held);
                        sp.getCooldowns().addCooldown(held, Math.max(40, (5 - (tier - 1)) * 20));
                     } else {
                        if (BossManager.isSeizing(sp)) {
                           BossManager.staffSeizeClick(sp);
                           return InteractionResult.SUCCESS;
                        }

                        String err = BossManager.useMindStaff(sp);
                        if (err != null) {
                           Chat.msg(sp, "&c" + err);
                           return InteractionResult.FAIL;
                        }
                     }

                     return InteractionResult.SUCCESS;
                  } else if (ModItems.isGolemFist(held)) {
                     if (sp.getCooldowns().isOnCooldown(held)) {
                        Chat.msg(sp, "&cGroundbreaker is still recharging.");
                        return InteractionResult.FAIL;
                     } else {
                        String err = BossManager.useGolemFist(sp);
                        if (err != null) {
                           Chat.msg(sp, "&c" + err);
                           return InteractionResult.FAIL;
                        } else {
                           int tier = ModItems.tierOf(held);
                           sp.getCooldowns().addCooldown(held, (9 - (tier - 1) * 2) * 20);
                           return InteractionResult.SUCCESS;
                        }
                     }
                  } else if (ModItems.isStoneStaff(held)) {
                     if (sp.getCooldowns().isOnCooldown(held)) {
                        Chat.msg(sp, "&cThe stone is still being called.");
                        return InteractionResult.FAIL;
                     } else {
                        String err = BossManager.useStoneStaff(sp);
                        if (err != null) {
                           Chat.msg(sp, "&c" + err);
                           return InteractionResult.FAIL;
                        } else {
                           int tier = ModItems.tierOf(held);
                           sp.getCooldowns().addCooldown(held, (12 - (tier - 1) * 3) * 20);
                           return InteractionResult.SUCCESS;
                        }
                     }
                  } else if (ModItems.isCaptainHorn(held)) {
                     if (sp.getCooldowns().isOnCooldown(held)) {
                        Chat.msg(sp, "&cThe horn is still recovering.");
                        return InteractionResult.FAIL;
                     } else {
                        String err = RaidGearManager.useCaptainHorn(sp);
                        if (err != null) {
                           Chat.msg(sp, "&c" + err);
                           return InteractionResult.FAIL;
                        } else {
                           // Horn cooldown scales with tier: I=100s, II=80s, III=60s
                           int tier = ModItems.tierOf(held);
                           sp.getCooldowns().addCooldown(held, (5 - (tier - 1)) * 20 * 20);
                           return InteractionResult.SUCCESS;
                        }
                     }
                  } else if (ModItems.isEvokerSpellbook(held)) {
                     if (sp.getCooldowns().isOnCooldown(held)) {
                        Chat.msg(sp, "&cThe spellbook is still recharging.");
                        return InteractionResult.FAIL;
                     }
                     String err = RaidGearManager.useEvokerSpellbook(sp);
                     if (err != null) {
                        Chat.msg(sp, "&c" + err);
                        return InteractionResult.FAIL;
                     } else {
                        sp.getCooldowns().addCooldown(held, sp.isShiftKeyDown() ? 500 : 120);
                        return InteractionResult.SUCCESS;
                     }
                  } else if (ModItems.isIllusionerSpellbook(held)) {
                     if (RaidGearManager.cancelIllusion(sp)) {
                        return InteractionResult.SUCCESS;
                     } else if (sp.getCooldowns().isOnCooldown(held)) {
                        Chat.msg(sp, "&cThe illusion is still settling.");
                        return InteractionResult.FAIL;
                     } else {
                        String err = RaidGearManager.useIllusionerSpellbook(sp);
                        if (err != null) {
                           Chat.msg(sp, "&c" + err);
                           return InteractionResult.FAIL;
                        } else {
                           sp.getCooldowns().addCooldown(held, 600); // 30 seconds
                           return InteractionResult.SUCCESS;
                        }
                     }
                  } else if (ModItems.isSlimeLauncher(held)) {
                     if (!ModConfig.is("boss")) {
                        Chat.msg(sp, "&cBoss gear is disabled on this server.");
                        return InteractionResult.FAIL;
                     } else {
                        // Right click fires it, outright. It used to hand the click back to vanilla
                        // (PASS) so the item could start a use action and be charged up - which is
                        // why the same button did nothing, then something, then more of it, and why
                        // the launcher was the one weapon in the mod that took your attack away.
                        BossManager.tryLauncherRightClick(sp);
                        return InteractionResult.SUCCESS;
                     }
                  } else {
                     if (player.isShiftKeyDown()) {
                        ItemStack worn = sp.getItemBySlot(EquipmentSlot.HEAD);
                        if (ModItems.isPossessedMask(worn)) {
                           String err = BossManager.usePossessedMask(sp);
                           if (err != null) {
                              sp.sendSystemMessage(Component.literal("§c" + err), true);
                              return InteractionResult.FAIL;
                           }

                           return InteractionResult.SUCCESS;
                        }
                     }

                     if (player.isShiftKeyDown()) {
                        ItemStack chest = sp.getItemBySlot(EquipmentSlot.CHEST);
                        if (ModItems.isMindbinderShroud(chest)) {
                           String err = BossManager.useMindbinderShroud(sp);
                           if (err != null) {
                              sp.sendSystemMessage(Component.literal("§c" + err), true);
                              return InteractionResult.FAIL;
                           }

                           return InteractionResult.SUCCESS;
                        }
                     }

                     if (!TokenManager.isToken(held) || !player.isShiftKeyDown()) {
                        return InteractionResult.PASS;
                     } else if (!ModConfig.is("token")) {                         Chat.msg(sp, "&cFavor tokens are disabled on this server.");
                        return InteractionResult.FAIL;
                     } else {
                        UUID creator = TokenManager.creatorUuid(held);
                        if (creator != null && creator.equals(sp.getUUID())) {
                           ItemStack token = held.copy();
                           held.setCount(0);
                           TokenRenameMenu.open(sp, token);
                           return InteractionResult.SUCCESS;
                        } else {
                           Chat.msg(sp, "&cOnly the creator can rename this token.");
                           return InteractionResult.FAIL;
                        }
                     }
                  }
               } else if (!ModConfig.is("boss")) {
                  Chat.msg(sp, "&cBoss loot is disabled on this server.");
                  return InteractionResult.FAIL;
               } else {
                  // Family index drives the loot-box menu's title/pool. Kept as
                  // a flat chain rather than a nested ternary so the id-to-family
                  // mapping stays easy to read and impossible to mis-parenthesise.
                  int fam = 0;
                  if (ModItems.isSlimeLootBox(held)) {
                     fam = 1;
                  } else if (ModItems.isGolemLootBox(held)) {
                     fam = 2;
                  } else if (ModItems.isMindLootBox(held)) {
                     fam = 3;
                  } else if (ModItems.isSnowLootBox(held)) {
                     fam = 4;
                  } else if (ModItems.isSculkLootBox(held)) {
                     fam = 5;
                  } else if (ModItems.isRaidLootBox(held)) {
                     fam = 6;
                  } else if (ModItems.isTimeLordLootBox(held)) {
                     fam = 7;
                  } else if (ModItems.isScarletLootBox(held)) {
                     fam = 8;
                  } else if (ModItems.isClockworkLootBox(held)) {
                     fam = 9;
                  } else if (ModItems.isStarboundLootBox(held)) {
                     fam = 10;
                  } else if (ModItems.isVoidshaperLootBox(held)) {
                     fam = 11;
                  } else if (ModItems.isSovereignLootBox(held)) {
                     fam = 12;
                  } else if (ModItems.isPuppeteerLootBox(held)) {
                     fam = 13;
                  } else if (ModItems.isDrownedLootBox(held)) {
                     fam = 14;
                  } else if (ModItems.isGaleLootBox(held)) {
                     fam = 15;
                  }
                  if (ModItems.isWitherLootBox(held)) {
                     // Same wither legendary pool as the King box, own title.
                     LootBoxMenu.open(sp, sp.isShiftKeyDown(), fam, "§8§l☠ Wither Loot Box");
                  } else {
                     LootBoxMenu.open(sp, sp.isShiftKeyDown(), fam);
                  }
                  return InteractionResult.SUCCESS;
               }
            }
         }
      } else {
         return InteractionResult.PASS;
      }
   }

   /**
    * The event layer's four refusal doors, each wrapped so a "no" reaches the client.
    *
    * <p>Every one of these handlers can refuse an action the client has already predicted
    * locally, and a refusal vanilla never hears about is a client and a server that disagree
    * until the next relog. The correction is one helper call per door, wrapped around the
    * whole handler rather than added to each refusal inside it: there are dozens of refusals
    * in {@link #onUseBlock} and {@link #onAttack} alone, and the one anybody adds next would
    * be the one that forgot. See {@link com.fortuneandfavors.util.ClientResync} for what each
    * door can be wrong about, and {@code build.gradle} for the audit rule that keeps these
    * wrappers in place.
    */
   private static InteractionResult refusedUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
      InteractionResult result = Safe.result("use-block", () -> onUseBlock(player, level, hand, hit));
      if (result == InteractionResult.FAIL && player instanceof ServerPlayer serverPlayer) {
         com.fortuneandfavors.util.ClientResync.refusedUse(serverPlayer, hit);
      }
      return result;
   }

   private static InteractionResult refusedItem(Player player, Level level, InteractionHand hand) {
      InteractionResult result = Safe.result("use-item", () -> onUseItem(player, level, hand));
      if (result == InteractionResult.FAIL && player instanceof ServerPlayer serverPlayer) {
         com.fortuneandfavors.util.ClientResync.hand(serverPlayer);
      }
      return result;
   }

   private static InteractionResult refusedAttack(Player player, Level level, Entity target) {
      InteractionResult result = Safe.result("claim attack", () -> onAttack(player, level, target));
      if (result == InteractionResult.FAIL && player instanceof ServerPlayer serverPlayer) {
         com.fortuneandfavors.util.ClientResync.refusedAttack(serverPlayer);
      }
      return result;
   }

   private static InteractionResult refusedEntityUse(Player player, Level level, Entity target, InteractionHand hand) {
      InteractionResult result = Safe.result("claim frame use", () -> onFrameUse(player, level, target, hand));
      if (result == InteractionResult.FAIL && player instanceof ServerPlayer serverPlayer) {
         com.fortuneandfavors.util.ClientResync.hand(serverPlayer);
      }
      return result;
   }

   private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
      if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
         if (DuelManager.isSpectating(serverPlayer.getUUID())) {
            return InteractionResult.FAIL;
         }

         // Frozen in a time stop: no placing, no opening, no interacting.
         if (com.fortuneandfavors.economy.TimeLordManager.isTimeStopped(serverPlayer)) {
            return InteractionResult.FAIL;
         }

         // The cell block has no building trades. A prisoner who can put a block down can wall off
         // his own tunnel behind him, bridge over the pit, or roof a guard into a corner, and the
         // mine stops being a place the block made. Nothing is *taken* - the stack is still in the
         // hand, unspent - so this is a rule about where the block goes and not about the inventory.
         // The rule itself lives in PrisonManager.mayPlace, where a test can ask it directly.
         if (!PrisonManager.mayPlace(serverPlayer, player.getItemInHand(hand))) {
            serverPlayer.sendOverlayMessage(
               net.minecraft.network.chat.Component.literal(
                  "\u00a77The block is not yours to build in \u00a78- \u00a7fyou are a prisoner, not a contractor."
               )
            );
            return InteractionResult.FAIL;
         }

         // Worn by the Puppeteer: same three doors, and this is the one a player would use
         // to open a chest and quietly re-gear while somebody else owns their hands.
         if (com.fortuneandfavors.economy.PuppeteerManager.isPossessed(serverPlayer)) {
            serverPlayer.sendOverlayMessage(
               net.minecraft.network.chat.Component.literal(
                  "\u00a75\u2726 \u00a7fYour hand is not your own \u00a78- \u00a7dkill him to get it back."
               )
            );
            return InteractionResult.FAIL;
         }

         if (NiceKeepInventoryManager.isEnabled()) {
            InteractionResult graveResult = Safe.result(
               "nicekeepinv claim",
               () -> {
                  BlockState hitState = level.getBlockState(hit.getBlockPos());
                  return (InteractionResult)((hitState.is(Blocks.PLAYER_HEAD) || hitState.is(Blocks.PLAYER_WALL_HEAD))
                        && NiceKeepInventoryManager.tryClaimGrave(serverPlayer, hit.getBlockPos(), level.getServer())
                     ? InteractionResult.SUCCESS
                     : InteractionResult.PASS);
               }
            );
            if (graveResult != InteractionResult.PASS) {
               return graveResult;
            }
         }

         InteractionResult mapEdit = MapEditor.onUseBlock(serverPlayer, level, hand, hit);
         if (mapEdit != InteractionResult.PASS) {
            return mapEdit;
         }

         if (player.getItemInHand(hand).isEmpty() && player.isShiftKeyDown()) {
            InteractionResult worn = tryWornGearAbility(serverPlayer, hand);
            if (worn != InteractionResult.PASS) {
               return worn;
            }
         }

         ItemStack held = player.getItemInHand(hand);
         Item item = held.getItem();
         BlockPos hitPos = hit.getBlockPos();
         if (DuelManager.isGoldenAppleHeadAllowed(serverPlayer) && DuelManager.isGoldenAppleHead(held) && DuelManager.eatGoldenAppleHead(serverPlayer, held)) {
            return InteractionResult.SUCCESS;
         }

         if (ModItems.isChunkClaimer(held)) {
            return useChunkClaimer(serverPlayer, level, hit);
         }

         // The Wooden Marionette is not a placeable thing, but the ground is exactly where
         // a player points it - "hang it up". Refusing the placement without answering the
         // click would make the item's own verb fail on the block it is aimed at, so the
         // block click is the summon, and the armor stand it is built on is refused below
         // for every other route (see ModItems.isNonPlaceable).
         if (ModItems.isWoodenMarionette(held)) {
            String err = com.fortuneandfavors.economy.PuppeteerManager.useWoodenMarionette(serverPlayer, held);
            if (err != null) {
               Chat.msg(serverPlayer, "&c" + err);
               return InteractionResult.FAIL;
            }
            return InteractionResult.SUCCESS;
         }

         // The Sorter Tag clicked at a container names it, so a sorter anywhere in reach can pull
         // out of it: see SorterLinks. This is answered *before* the non-placeable refusal below,
         // because the tag is a sign and must never be placed - its whole click is the tag. It is
         // the only item that tags: an ordinary vanilla sign is deliberately left alone here, so
         // the signs people put on their chests to label them stay signs.
         if (ModItems.isSorterTag(held) && level.getBlockEntity(hitPos) instanceof net.minecraft.world.Container) {
            if (serverPlayer.isShiftKeyDown()) {
               if (!com.fortuneandfavors.economy.SorterLinks.unmark(serverPlayer, level, hitPos)) {
                  Chat.msg(serverPlayer, "&7That container has no tag to remove.");
               }
            } else {
               com.fortuneandfavors.economy.SorterLinks.mark(serverPlayer, level, hitPos, heldTagName(held));
            }
            return InteractionResult.SUCCESS;
         }

         // The Super Smelter's tiers live behind the click vanilla does not want. A plain
         // right-click is the furnace's own window and has to stay that way - it is the only way to
         // put ore in - so the sneak click is the machine's *own* window, and that is all it is.
         // It used to pick the machine up whenever the hand was not empty, which is not a gesture
         // anybody can aim: a player who shift-clicks a smelter holding an ingot was trying to open
         // it, and got the whole furnace in their hand instead. The pickup button in the window is
         // how a smelter moves now, so intercepting the sneak click whatever the hand holds takes
         // nothing away - and it is what makes "sneak-right-click opens the GUI" true. See
         // MachineUpgradeMenu.
         if (serverPlayer.isShiftKeyDown() && MachineManager.isSuperSmelter(level, hitPos)) {
            com.fortuneandfavors.menu.MachineUpgradeMenu.open(serverPlayer, hitPos);
            return InteractionResult.SUCCESS;
         }

         if (ModItems.isNonPlaceable(held)) {
            return InteractionResult.SUCCESS;
         }

         // Anticheat (off unless an admin switched it on): a block placed directly
         // under a player who is falling, without looking down, is scaffold - a
         // client can aim at its own feet, a person cannot.
         if (held.getItem() instanceof BlockItem) {
            BlockPos placePos = hit.getBlockPos().relative(hit.getDirection());
            if (level.getBlockState(placePos).canBeReplaced()
               && !com.fortuneandfavors.anticheat.AntiCheat.onBlockPlaceAttempt(serverPlayer, placePos)) {
               return InteractionResult.FAIL;
            }
         }

         if (DuelManager.isBedwarsDuel(serverPlayer)) {
            if (level.getBlockState(hitPos).is(Blocks.CRAFTING_TABLE)) {
               Chat.msg(serverPlayer, "&cCrafting is disabled in Bed Wars!");
               return InteractionResult.FAIL;
            }

            if (item == Blocks.CRAFTING_TABLE.asItem()) {
               Chat.msg(serverPlayer, "&cCrafting is disabled in Bed Wars!");
               return InteractionResult.FAIL;
            }
         }

         if (level.getBlockState(hitPos).is(Blocks.SPAWNER)) {
            SpawnerMenu.open(serverPlayer, hitPos);
            return InteractionResult.SUCCESS;
         }

         if (com.fortuneandfavors.block.ChairBlock.isChair(level, hitPos)) {
            return com.fortuneandfavors.block.ChairBlock.onUse(serverPlayer, level, hitPos);
         }

         // Expedition interactables: whatever this run planted - a wager pedestal, a sealed
         // reliquary, a rune stone, a supply crate - answers here, by exact position rather than
         // by block type. Chests are deliberately left to the chest path below, which already
         // turns one into secured loot.
         if (ExpeditionManager.isInExpedition(serverPlayer.getUUID())
            && !level.getBlockState(hitPos).is(Blocks.CHEST)) {
            InteractionResult expUse = ExpeditionManager.onInteract(serverPlayer, hitPos);
            if (expUse != null) {
               return expUse;
            }
         }

         if (!player.isShiftKeyDown() || !MachineManager.isAnyMachine(level, hitPos) || MachineManager.isRedeemer(level, hitPos) && TokenManager.isToken(held)) {
            String type = ModItems.typeOf(held);
            if ("auto_sell_hopper".equals(type) && !ModConfig.is("autosell")) {
               Chat.msg(serverPlayer, "&cAuto-sell hoppers are disabled on this server.");
               return InteractionResult.FAIL;
            }

            if ("elevator".equals(type) && !ModConfig.is("elevator")) {
               Chat.msg(serverPlayer, "&cElevators are disabled on this server.");
               return InteractionResult.FAIL;
            }

            if ("token_redeemer".equals(type) && !ModConfig.is("token")) {
               Chat.msg(serverPlayer, "&cToken redeemers are disabled on this server.");
               return InteractionResult.FAIL;
            }

            if ("spawner_infuser".equals(type) && !ModConfig.is("exclusive")) {
               Chat.msg(serverPlayer, "&cSpawner infusers are disabled on this server.");
               return InteractionResult.FAIL;
            }

            if ("item_forge".equals(type) && !ModConfig.is("boss")) {
               Chat.msg(serverPlayer, "&cBoss gear is disabled on this server.");
               return InteractionResult.FAIL;
            }

            // The eight late-game machines: the five "exclusive" shop blocks behind that gate, the
            // three farm machines behind the auto-sell gate they were built to work with.
            if (("chunk_anchor".equals(type) || "repair_station".equals(type) || "item_sorter".equals(type)
               || "portable_furnace".equals(type) || "portable_campfire".equals(type)) && !ModConfig.is("exclusive")) {
               Chat.msg(serverPlayer, "&cThat machine is disabled on this server.");
               return InteractionResult.FAIL;
            }

            if (("auto_planter".equals(type) || "auto_harvester".equals(type) || "irrigation_sprinkler".equals(type))
               && !ModConfig.is("autosell")) {
               Chat.msg(serverPlayer, "&cFarm machines are disabled on this server.");
               return InteractionResult.FAIL;
            }

            // The anchor limit is enforced at the block and not at the shop: a player may hold up
            // to three, each dearer than the last, and the refusal only comes when the fourth is
            // put down. See ChunkAnchor.canPlace.
            if ("chunk_anchor".equals(type)
               && com.fortuneandfavors.economy.ChunkAnchor.ownedCount(serverPlayer.getUUID())
                  >= com.fortuneandfavors.economy.ChunkAnchor.PER_PLAYER) {
               com.fortuneandfavors.economy.ChunkAnchor.refuse(serverPlayer);
               return InteractionResult.FAIL;
            }

            if (MachineManager.isInfuser(level, hitPos)) {
               InfuserMenu.open(serverPlayer);
               return InteractionResult.SUCCESS;
            }

            if (MachineManager.isItemForge(level, hitPos)) {
               ItemForgeMenu.open(serverPlayer);
               return InteractionResult.SUCCESS;
            }

            if (MachineManager.isAutoSellHopper(level, hitPos)) {
               AutoSellMenu.open(serverPlayer, hitPos);
               return InteractionResult.SUCCESS;
            }

            // The Repair Station: the anvil's job, paid for in money instead of levels.
            if (MachineManager.isRepairStation(level, hitPos)) {
               com.fortuneandfavors.menu.RepairStationMenu.open(serverPlayer, hitPos);
               return InteractionResult.SUCCESS;
            }

            // The Super Hopper's tiers. It is a vanilla hopper underneath and a vanilla hopper has
            // no window at all, so the click the block does not want - an empty hand - is where the
            // dials live. See MachineUpgradeMenu and MachineTuning.
            if (MachineManager.isSuperHopper(level, hitPos) && held.isEmpty()) {
               com.fortuneandfavors.menu.MachineUpgradeMenu.open(serverPlayer, hitPos);
               return InteractionResult.SUCCESS;
            }

            // The Checker Hopper's one switch. The machine has no filter to open - it keeps
            // whatever no sorter wants - so its whole configuration is "hand it on" or "destroy
            // it", and an empty hand flips it, the same click the hopper block does not use.
            if (MachineManager.isCheckerHopper(level, hitPos) && held.isEmpty()) {
               String key = MachineManager.keyFor(level, hitPos);
               boolean now = !com.fortuneandfavors.economy.MachineTuning.voiding(key);
               com.fortuneandfavors.economy.MachineTuning.setVoiding(key, now);
               Chat.msg(serverPlayer, now
                  ? "&aItem Checker Hopper set to &lvoid&a: it now destroys the junk it catches instead of passing it on."
                  : "&7Item Checker Hopper set to &fpass on&7: it hands the junk onward again.");
               com.fortuneandfavors.util.SoundUtil.play(serverPlayer, now ? ModSounds.BUY : ModSounds.DENY);
               return InteractionResult.SUCCESS;
            }

            // The Item Sorter: an empty hand opens its window (the filter, as the items themselves,
            // plus what it reads from and where it puts things), a bag clicked at it hands over its
            // whole contents as a filter, and any other item is filtered in or out exactly as
            // before.
            //
            // A sneak-click never arrives here: the block above skips every machine while the player
            // is sneaking, because that is the gesture that picks the machine up. The old
            // sneak-to-cycle-the-reading branch was therefore dead code - the reading is cycled from
            // the window, which is the only surface the player can actually reach.
            if (MachineManager.isItemSorter(level, hitPos)) {
               if (held.isEmpty()) {
                  com.fortuneandfavors.menu.ItemSorterMenu.open(serverPlayer, hitPos);
               } else if (held.getItem() instanceof net.minecraft.world.item.BlockItem block
                  && block.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                  com.fortuneandfavors.economy.ItemSorter.importFrom(serverPlayer, level, hitPos, held);
               } else if (ModItems.isBundle(held) || ModItems.isBackpack(held)) {
                  com.fortuneandfavors.economy.ItemSorter.importFrom(serverPlayer, level, hitPos, held);
               } else {
                  com.fortuneandfavors.economy.ItemSorter.toggleItem(serverPlayer, level, hitPos, held);
               }
               return InteractionResult.SUCCESS;
            }

            // The Transfer Hopper: the same window, in its transfer shape - no filter, tag links in
            // and out, and everything it holds sent onward. The Overflow Hopper is the same window
            // again, with the tag described as a spillway and a live full/room line at the top. See
            // ItemSorterMenu.isTransfer.
            if (MachineManager.isTransferFamily(level, hitPos)) {
               if (held.isEmpty()) {
                  com.fortuneandfavors.menu.ItemSorterMenu.open(serverPlayer, hitPos);
               }
               return InteractionResult.SUCCESS;
            }

            // The Super Smelter is an ordinary furnace underneath, so its GUI is the one vanilla
            // already has: the click is handed straight back, exactly like the portable kitchen.
            if (MachineManager.isSuperSmelter(level, hitPos)) {
               return InteractionResult.PASS;
            }

            // The three farm machines: one window between them, because they are one machine in
            // three costumes. See FarmMachineMenu.
            if (MachineManager.isAutoPlanter(level, hitPos) || MachineManager.isAutoHarvester(level, hitPos)
               || MachineManager.isIrrigationSprinkler(level, hitPos)) {
               String farmType = MachineManager.isAutoPlanter(level, hitPos)
                  ? MachineManager.TYPE_AUTO_PLANTER
                  : MachineManager.isAutoHarvester(level, hitPos) ? MachineManager.TYPE_AUTO_HARVESTER : MachineManager.TYPE_IRRIGATION_SPRINKLER;
               com.fortuneandfavors.menu.FarmMachineMenu.open(serverPlayer, hitPos, farmType);
               return InteractionResult.SUCCESS;
            }

            // The Portable Furnace and Campfire are ordinary furnaces and smokers underneath: the
            // GUI they want is the one vanilla already has, so the click is handed straight back.
            if (MachineManager.isPortableFurnace(level, hitPos) || MachineManager.isPortableCampfire(level, hitPos)) {
               return InteractionResult.PASS;
            }

            // The Chunk Anchor: the machine itself is simply on, but its window is how a player
            // sees which nine chunks they are paying for - and where the next anchor is bought.
            if (MachineManager.isChunkAnchor(level, hitPos)) {
               com.fortuneandfavors.menu.ChunkAnchorMenu.open(serverPlayer, hitPos);
               return InteractionResult.SUCCESS;
            }

            if (MachineManager.isElevator(level, hitPos)) {
               return InteractionResult.FAIL;
            }

            if (MachineManager.isRedeemer(level, hitPos)) {
               if (TokenManager.isToken(held)) {
                  MachineManager.redeem(serverPlayer, level, hitPos, held);
               } else {
                  RedeemerMenu.open(serverPlayer, hitPos);
               }

               return InteractionResult.SUCCESS;
            } else {
               if (player.isShiftKeyDown() && held.is(Items.IRON_INGOT)) {
                  BlockState hitState = level.getBlockState(hitPos);
                  net.minecraft.world.level.block.Block repairTarget = null;
                  String repairedName = null;
                  if (hitState.is(Blocks.DAMAGED_ANVIL)) {
                     repairTarget = Blocks.CHIPPED_ANVIL;
                     repairedName = "&echipped";
                  } else if (hitState.is(Blocks.CHIPPED_ANVIL)) {
                     repairTarget = Blocks.ANVIL;
                     repairedName = "&afully repaired";
                  }

                  if (repairTarget != null) {
                     if (!ClaimManager.canBuild(serverPlayer, hitPos)) {
                        ClaimManager.warnClaimed(serverPlayer, "&cThis land is claimed - you can't repair that anvil.");
                        return InteractionResult.FAIL;
                     }

                     level.setBlock(hitPos, repairTarget.withPropertiesOf(hitState), 3);
                     if (!player.getAbilities().instabuild) {
                        held.shrink(1);
                     }

                     double x = hitPos.getX() + 0.5;
                     double y = hitPos.getY() + 0.5;
                     double z = hitPos.getZ() + 0.5;
                     if (level instanceof ServerLevel sl) {
                        com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.LARGE_SMOKE, x, y + 0.5, z, 8, 0.15, 0.1, 0.15, 0.02);
                        com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.FIREWORK, x, y + 0.3, z, 6, 0.2, 0.15, 0.2, 0.015);
                        com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ENCHANT, x, y, z, 12, 0.3, 0.4, 0.3, 0.06);
                     }

                     SoundSource src = SoundSource.BLOCKS;
                     level.playSound(null, x, y, z, SoundEvents.ANVIL_USE, src, 0.8F, 1.2F);
                     level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_BLAST, src, 0.4F, 2.0F);
                     Chat.raw(serverPlayer, "&7Anvil repaired to " + repairedName + "&7! (1 iron ingot consumed)");
                     return InteractionResult.SUCCESS;
                  }

                  if (hitState.is(Blocks.ANVIL)) {
                     Chat.msg(serverPlayer, "&7This anvil is already in perfect condition.");
                  }
               }

               boolean buySign = ModItems.isBuySign(held);
               boolean sellSign = ModItems.isSellSign(held);
               if (!buySign && !sellSign) {
                  if (held.getItem() instanceof BlockItem && !DuelManager.isDuelRealm(level)) {
                     BlockPos placePos = hitPos.relative(hit.getDirection());
                     if (!ClaimManager.canBuild(serverPlayer, placePos)) {
                        ClaimManager.warnClaimed(serverPlayer, "&cThis land is claimed - you can't build here.");
                        return InteractionResult.FAIL;
                     }
                  }

                  if (held.getItem() instanceof BucketItem bucket && !bucket.getContent().isSame(Fluids.EMPTY)) {
                     BlockPos placePos = hitPos.relative(hit.getDirection());
                     if (!ClaimManager.canBuild(serverPlayer, hitPos) || !ClaimManager.canBuild(serverPlayer, placePos)) {
                        ClaimManager.warnClaimed(serverPlayer, "&cThis land is claimed - you can't place fluids here.");
                        return InteractionResult.FAIL;
                     }
                  }

                  if (!(level.getBlockState(hitPos).getBlock() instanceof ChestBlock)) {
                     if (level.getBlockEntity(hitPos) instanceof Container && !ClaimManager.canOpenChest(serverPlayer, hitPos)) {
                        ClaimManager.warnClaimed(serverPlayer, "&cThis land is claimed - you can't open containers here.");
                        return InteractionResult.FAIL;
                     } else if (!ClaimManager.canBuild(serverPlayer, hitPos)) {
                        ClaimManager.warnClaimed(serverPlayer, "&cThis land is claimed - you can't use that here.");
                        return InteractionResult.FAIL;
                     } else {
                        return InteractionResult.PASS;
                     }
                  } else {
                     if (item instanceof BlockItem bi && bi.getBlock() instanceof ChestBlock) {
                        BlockPos placePos = hitPos.relative(hit.getDirection());

                        for (Direction dir : Plane.HORIZONTAL) {
                           ChestShop neighbor = ChestShopManager.get(level, placePos.relative(dir));
                           if (neighbor != null && !neighbor.owner.equals(serverPlayer.getUUID())) {
                              Chat.msg(serverPlayer, "&cYou can't place a chest against " + ownerName(level, neighbor.owner) + "'s shop!");
                              return InteractionResult.FAIL;
                           }
                        }
                     }

                     // Expedition chests: opening instantly converts contents to loot money.
                     if (ExpeditionManager.isInExpedition(serverPlayer.getUUID())
                        && level.getBlockEntity(hitPos) instanceof ChestBlockEntity) {
                        ExpeditionManager.onChestOpen(serverPlayer, hitPos);
                        return InteractionResult.SUCCESS;
                     }

                     ChestShop shop = ChestShopManager.get(level, hitPos);
                     // An owner can always get back into their own settings, but a
                     // *closed* shop is just a chest to everyone else - so trading
                     // falls back to the ordinary chest path (claim checks and all)
                     // instead of a trade window that would refuse every click.
                     if (shop != null && !ChestShopManager.isTrading(shop) && !shop.owner.equals(serverPlayer.getUUID())) {
                        Chat.msg(serverPlayer, "&7This shop is closed right now - it's just a chest until the owner reopens it.");
                        shop = null;
                     }

                     if (shop != null) {
                        if (!ModConfig.is("exclusive")) {
                           return InteractionResult.PASS;
                        } else if (!(level.getBlockEntity(hitPos) instanceof ChestBlockEntity chest)) {
                           Chat.msg(serverPlayer, "&cThis shop chest is missing its inventory.");
                           return InteractionResult.FAIL;
                        } else if (shop.owner.equals(serverPlayer.getUUID())) {
                           ChestShopConfigMenu.open(serverPlayer, chest, shop);
                           return InteractionResult.SUCCESS;
                        } else {
                           ChestShopMenu.open(serverPlayer, chest, shop);
                           return InteractionResult.SUCCESS;
                        }
                     } else if (DuelManager.isTeamChest(serverPlayer, hitPos)) {
                        if (!DuelManager.canOpenDuelChest(serverPlayer, hitPos)) {
                           Chat.msg(serverPlayer, "&cThat's the enemy team's chest!");
                           return InteractionResult.FAIL;
                        } else {
                           DuelManager.describeTeamChest(serverPlayer, hitPos);
                           return InteractionResult.SUCCESS;
                        }
                     } else if (!DuelManager.canOpenDuelChest(serverPlayer, hitPos)) {
                        Chat.msg(serverPlayer, "&cThat's the enemy team's chest - you can't open it!");
                        return InteractionResult.FAIL;
                     } else if (level.getBlockEntity(hitPos) instanceof Container && !ClaimManager.canOpenChest(serverPlayer, hitPos)) {
                        ClaimManager.warnClaimed(serverPlayer, "&cThis land is claimed - you can't open chests here.");
                        return InteractionResult.FAIL;
                     } else {
                        return InteractionResult.PASS;
                     }
                  }
               } else {
                  // Holding a shop sign and clicking something that is not a chest:
                  // plain click swallows the interaction (so a sign is never wasted
                  // by accident), but a *sneak* click hands it back to vanilla so
                  // the sign can still be placed as an ordinary oak sign anywhere.
                  if (!(level.getBlockState(hitPos).getBlock() instanceof ChestBlock)) {
                     return player.isShiftKeyDown() ? InteractionResult.PASS : InteractionResult.SUCCESS;
                  }

                  if (!ModConfig.is("exclusive")) {
                     Chat.msg(serverPlayer, "&cExclusive chest shops are disabled on this server.");
                     return InteractionResult.FAIL;
                  }

                  String signType = buySign ? "buy" : "sell";
                  // Idempotent: clicking your own shop with the same sign type
                  // just opens the config menu instead of erroring - useful when
                  // the sign item was never consumed (creative) or the owner
                  // forgot which sign they used.
                  ChestShop existing = ChestShopManager.get(level, hitPos);
                  if (existing != null) {
                     if (existing.owner.equals(serverPlayer.getUUID()) && signType.equals(existing.type)
                        && level.getBlockEntity(hitPos) instanceof ChestBlockEntity existingChest) {
                        Chat.msg(serverPlayer, "&7This chest is already your " + signType + " shop - opening its settings.");
                        ChestShopConfigMenu.open(serverPlayer, existingChest, existing);
                        return InteractionResult.SUCCESS;
                     }

                     Chat.msg(serverPlayer, "&cThis chest is already a " + existing.type + " shop owned by " + ownerName(level, existing.owner) + ".");
                     return InteractionResult.FAIL;
                  }

                  if (ChestShopManager.createShop(serverPlayer, hitPos, signType)) {
                     // Placed either way (plain or sneak click), exactly like a sign
                     // on a wall - but sneak-clicking the chest afterwards is what
                     // reopens these settings, so say so once, here.
                     if (!player.getAbilities().instabuild) {
                        held.shrink(1);
                     }

                     Chat.msg(serverPlayer, "&7Sneak-right-click this chest any time to reopen the shop settings.");

                     ChestShopManager.save(level.getServer());
                     SoundUtil.play(serverPlayer, ModSounds.SHOP_CREATE);
                     return InteractionResult.SUCCESS;
                  } else {
                     return InteractionResult.FAIL;
                  }
               }
            }
         } else {
            if (com.fortuneandfavors.block.ChairBlock.isChair(level, hitPos)) {
               com.fortuneandfavors.block.ChairBlock.pickUp(serverPlayer, level, hitPos);
            } else if (MachineManager.isSuperSmelter(level, hitPos)) {
               // A Super Smelter's sneak click is its tier window and never a pickup - see above.
               // Reached only if that branch is ever reordered, and it must not become a pickup if
               // it is: the machine is moved from its own window.
               com.fortuneandfavors.menu.MachineUpgradeMenu.open(serverPlayer, hitPos);
            } else {
               MachineManager.pickUp(serverPlayer, level, hitPos);
            }
            return InteractionResult.SUCCESS;
         }
      } else {
         return InteractionResult.PASS;
      }
   }

   private static InteractionResult useChunkClaimer(ServerPlayer player, Level level, BlockHitResult hit) {
      if (!ModConfig.is("claims")) {
         Chat.msg(player, "&cLand claims are disabled on this server.");
         return InteractionResult.SUCCESS;
      }

      BlockPos clicked = hit.getBlockPos();
      if (player.isShiftKeyDown()) {
         Claim claim = ClaimManager.claimAt(level, clicked);
         if (claim == null) {
            claim = ClaimManager.claimAt(player);
         }

         if (claim == null) {
            Chat.msg(player, "&cThat's not inside a claimed area.");
         } else if (!ClaimManager.isOwnerOrAdmin(claim, player.getUUID())) {
            Chat.msg(player, "&cOnly the owner or an admin can change permissions on this claim.");
         } else {
            ClaimPermsMenu.openAll(player, claim);
         }

         return InteractionResult.SUCCESS;
      } else {
         ClaimManager.handleChunkClaim(player, clicked);
         return InteractionResult.SUCCESS;
      }
   }

   private static boolean onBlockBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
      if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
         if (DuelManager.isSpectating(serverPlayer.getUUID())) {
            return false;
         } else if (level instanceof ServerLevel slG && NiceKeepInventoryManager.isGraveHead(slG, pos)) {
            Chat.msg(serverPlayer, "&cThis grave is sealed! &7Right-click to claim it.");
            return false;
         } else {
            if (DuelManager.isInDuel(serverPlayer.getUUID())) {
               DuelManager.quickStoreToChest(serverPlayer, pos);
               if (DuelManager.isTeamChest(serverPlayer, pos)) {
                  return false;
               }

               if (level instanceof ServerLevel sl && DuelManager.isOwnBed(serverPlayer, sl, pos)) {
                  Chat.msg(serverPlayer, "&cYou can't break your own bed!");
                  return false;
               }

               if (DuelManager.isProtectedDuelBlock(serverPlayer, pos)) {
                  return false;
               }

               boolean allowArenaBlock = false;
               if (level instanceof ServerLevel sl2 && DuelManager.isBedwarsBed(sl2, pos)) {
                  allowArenaBlock = true;
               }

               // A Gladiator map is meant to be dug up: the ore in it is the kit.
               if (DuelManager.canBreakArenaMap(serverPlayer)) {
                  allowArenaBlock = true;
               }

               if (!allowArenaBlock && !DuelManager.isPlayerPlacedDuelBlock(serverPlayer, pos)) {
                  Chat.msg(serverPlayer, "&cYou can't break the arena map! &7Only blocks placed by fighters can be broken.");
                  return false;
               }
            }

            // The prison's own works: the band under the seam, the walls and the ceiling of a
            // sector. Asked before anything else about the prison, because it is the answer to a
            // break that is not a refusal of a rule but the boundary of a place - see
            // PrisonManager.isBoundary.
            if (com.fortuneandfavors.economy.PrisonManager.refuseBreak(serverPlayer, pos)) {
               return false;
            }

            if (BossManager.isShockBlock(pos)) {
               return false;
            }

            if (BossManager.isPrisonBlock(pos)) {
               UUID owner = BossManager.prisonOwnerOf(pos);
               if (owner != null && owner.equals(serverPlayer.getUUID())) {
                  if (level instanceof ServerLevel sl) {
                     BossManager.onPrisonSwing(sl, serverPlayer);
                  }

                  long now = level.getGameTime();
                  if (PRISON_MSG_COOLDOWN.getOrDefault(serverPlayer.getUUID(), 0L) <= now) {
                     PRISON_MSG_COOLDOWN.put(serverPlayer.getUUID(), now + 40L);
                     Chat.msg(serverPlayer, "&bYou're sealed in ice - &fmas attack to shatter it, or a friend can mine you out!");
                  }

                  return false;
               }
            }

         if (BossManager.isFullControlled(serverPlayer.getUUID())) {
            return false;
         }

         // Expedition boundary protection: prevent breaking floor/walls/ceiling to escape
         if (ExpeditionManager.isProtectedBoundary(serverPlayer, pos)) {
            Chat.msg(serverPlayer, "&cThe expedition walls are unbreakable - you can't dig your way out!");
            return false;
         }

         // The bars a chamber put up behind you: a lock that a pickaxe opens is not a lock.
         if (ExpeditionManager.isSealedDoorBar(serverPlayer, pos)) {
            Chat.msg(serverPlayer, "&cThe bars hold until the pack in here is dead.");
            return false;
         }

         if (!ClaimManager.canBuild(serverPlayer, pos)) {
               ClaimManager.warnClaimed(serverPlayer, "&cThis land is claimed - you can't break blocks here.");
               return false;
            }

            ChestShop shop = ChestShopManager.get(level, pos);
            if (shop == null && state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
               BlockPos partner = ChestBlock.getConnectedBlockPos(pos, state);
               if (partner != null) {
                  shop = ChestShopManager.get(level, partner);
               }
            }

            if (shop != null && ModConfig.is("exclusive") && !shop.owner.equals(serverPlayer.getUUID())) {
               Chat.msg(serverPlayer, "&cThis chest is a shop owned by " + ownerName(level, shop.owner) + "! Only the owner can break it.");
               return false;
            } else if (MachineManager.isAnyMachine(level, pos)) {
               Chat.msg(serverPlayer, "&cThis machine can't be broken - sneak-right-click it to pick it up.");
               return false;
            } else if (state.is(Blocks.SPAWNER) && !SpawnerManager.canBreak(level, pos, serverPlayer)) {
               Chat.msg(
                  serverPlayer, "&cThis spawner belongs to " + ownerName(level, SpawnerManager.get(level, pos).owner()) + "! Only the owner can break it."
               );
               return false;
            } else {
               return true;
            }
         }
      } else {
         return true;
      }
   }

   private static boolean isFrameLike(Entity e) {
      return e instanceof ItemFrame || e instanceof ArmorStand || e instanceof Display;
   }

   private static InteractionResult onFrameUse(Player player, Level level, Entity entity, InteractionHand hand) {
      if (!level.isClientSide() && player instanceof ServerPlayer sp) {
         // Expedition Supplier: replace the vanilla (empty) villager trades with
         // the mod's supplier shop that spends secured expedition loot.
         if (entity instanceof net.minecraft.world.entity.npc.villager.Villager villager) {
            net.minecraft.network.chat.Component cn = villager.getCustomName();
            String name = cn == null ? "" : cn.getString();
            if (name.contains("Expedition Supplier") && ExpeditionManager.isInExpedition(sp.getUUID())) {
               com.fortuneandfavors.menu.ExpeditionSupplierMenu.open(sp);
               return InteractionResult.SUCCESS;
            }
            // The Expedition Broker is the permanent shop's door inside a run: it only exists in a
            // site, but the window it opens is the very same one /expedition shop opens. Matched by
            // name for the same reason the supplier above is - it is an ordinary villager, and the
            // name is the only thing that says which counter it is.
            if (name.contains("Expedition Broker")) {
               com.fortuneandfavors.menu.ExpeditionShopMenu.open(sp);
               return InteractionResult.SUCCESS;
            }
         }
         InteractionResult bw = DuelManager.onVillagerUse(sp, entity);
         if (bw != InteractionResult.PASS) {
            return bw;
         }

         InteractionResult me = MapEditor.onUseEntity(sp, entity);
         if (me != InteractionResult.PASS) {
            return me;
         }

         if (sp.getItemInHand(hand).isEmpty() && sp.isShiftKeyDown()) {
            InteractionResult worn = tryWornGearAbility(sp, hand);
            if (worn != InteractionResult.PASS) {
               return worn;
            }
         }

         if (!isFrameLike(entity)) {
            return InteractionResult.PASS;
         } else if (!ClaimManager.canBuild(sp, entity.blockPosition())) {
            Chat.msg(sp, "&cYou can't touch that - it's in claimed land.");
            return InteractionResult.FAIL;
         } else {
            return InteractionResult.PASS;
         }
      } else {
         return InteractionResult.PASS;
      }
   }

   /**
    * Every item type the right-click chain in {@link #onUseItem} handles, keyed by
    * the {@code ff} type tag and valued by a human-readable handler name.
    *
    * <p>This exists so "every custom item has a working right-click" is something
    * the self-test can assert instead of something a person checks by scrolling
    * logs. It is deliberately kept beside the dispatch chain it describes, and
    * {@code ffAuditSources} fails the build if any predicate used inside
    * {@code onUseItem} is missing from this table - so the table cannot drift out
    * of step with the code it claims to cover.
    */
   private static final java.util.Map<String, String> USE_HANDLERS = java.util.Map.ofEntries(
      java.util.Map.entry("backpack", "backpack / ender backpack"),
      java.util.Map.entry("bundle", "bundle"),
      java.util.Map.entry("potion_belt", "potion belt"),
      java.util.Map.entry("expedition_compass", "expedition compass - erase a fatal-blow wait"),
      java.util.Map.entry("buy_sign", "place a chest-shop sign on a chest"),
      java.util.Map.entry("sell_sign", "place a chest-shop sign on a chest"),
      java.util.Map.entry("death_compass", "death compass sonar"),
      java.util.Map.entry("bounty_compass", "bounty compass ping"),
      java.util.Map.entry("sculk_food", "sculk fruit"),
      java.util.Map.entry("mystery_box", "mystery box spin"),
      java.util.Map.entry("raid_boss_token", "raid boss summon"),
      java.util.Map.entry("slime_boss_token", "slime king summon"),
      java.util.Map.entry("stone_golem_token", "stone golem summon"),
      java.util.Map.entry("snow_queen_token", "snow queen summon"),
      java.util.Map.entry("sculk_medallion", "elder warden summon"),
      java.util.Map.entry("space_time_rift", "time lord summon"),
      java.util.Map.entry("scarlet_blood", "scarlet devil summon"),
      java.util.Map.entry("clockwork_core", "clockwork king summon"),
      java.util.Map.entry("clockwork_loot_box", "clockwork king loot box spin"),
      java.util.Map.entry("astral_compass", "starbound magister summon"),
      java.util.Map.entry("starbound_loot_box", "starbound magister loot box spin"),
      java.util.Map.entry("magisters_codex", "magister's codex spell"),
      java.util.Map.entry("void_anchor", "void shaper summon"),
      java.util.Map.entry("voidshaper_loot_box", "void shaper loot box spin"),
      java.util.Map.entry("shaping_sigil", "shaping sigil block throw"),
      java.util.Map.entry("sovereigns_crown", "emerald sovereign summon"),
      java.util.Map.entry("sovereign_loot_box", "emerald sovereign loot box spin"),
      java.util.Map.entry("wooden_marionette", "puppeteer summon"),
      java.util.Map.entry("puppeteer_loot_box", "puppeteer loot box spin"),
      java.util.Map.entry("marionette_strings", "marionette strings tie and pull"),
      java.util.Map.entry("royal_contract", "royal contract guard"),
      java.util.Map.entry("sovereigns_bell", "sovereign's bell"),
      java.util.Map.entry("emerald_seal", "emerald seal tribute"),
      java.util.Map.entry("time_lord_loot_box", "time lord loot box spin"),
      java.util.Map.entry("scarlet_loot_box", "scarlet devil loot box spin"),
      java.util.Map.entry("king_loot_box", "king loot box spin"),
      java.util.Map.entry("wither_loot_box", "wither loot box spin"),
      java.util.Map.entry("slime_loot_box", "slime loot box spin"),
      java.util.Map.entry("golem_loot_box", "golem loot box spin"),
      java.util.Map.entry("mind_loot_box", "mindbinder loot box spin"),
      java.util.Map.entry("snow_loot_box", "snow queen loot box spin"),
      java.util.Map.entry("sculk_loot_box", "elder warden loot box spin"),
      java.util.Map.entry("raid_loot_box", "raid loot box spin"),
      java.util.Map.entry("wormhole_potion", "wormhole"),
      java.util.Map.entry("wither_staff", "wither staff / multidimensional army"),
      java.util.Map.entry("wither_cloak_sword", "wither cloak"),
      java.util.Map.entry("slime_launcher", "slime launcher"),
      java.util.Map.entry("stone_staff", "stone staff"),
      java.util.Map.entry("golem_fist", "golem's fist"),
      java.util.Map.entry("ice_staff", "ice staff"),
      java.util.Map.entry("frostbound_crown", "frostbound bow"),
      java.util.Map.entry("mind_staff", "mindbinder staff"),
      java.util.Map.entry("mindbinder_eye", "mindbinder eye"),
      java.util.Map.entry("mind_shroud", "mindbinder shroud"),
      java.util.Map.entry("possessed_mask", "possessed mask"),
      java.util.Map.entry("sculk_mage_staff", "sculk mage staff"),
      java.util.Map.entry("sculk_orb", "sculk orb"),
      java.util.Map.entry("sculk_sensor_leggings", "sculk sensor leggings"),
      java.util.Map.entry("wardens_call", "warden's call"),
      java.util.Map.entry("slime_boots", "slime boots"),
      java.util.Map.entry("captain_horn", "raid captain's horn"),
      java.util.Map.entry("evoker_spellbook", "evoker's spellbook"),
      java.util.Map.entry("illusioner_spellbook", "illusioner's spellbook"),
      java.util.Map.entry("raid_banner", "raid banner"),
      java.util.Map.entry("scarlet_grimoire", "scarlet grimoire spell"),
      java.util.Map.entry("blood_prism", "blood prism ritual"),
      java.util.Map.entry("pocket_watch", "pocket-watch time stop"),
      java.util.Map.entry("chrono_shard", "chrono shard rewind"),
      java.util.Map.entry("hourglass_of_haste", "hourglass of haste"),
      java.util.Map.entry("rune_haste", "apply rune of haste"),
      java.util.Map.entry("rune_flame", "apply rune of flame"),
      java.util.Map.entry("rune_fortune", "apply rune of fortune"),
      java.util.Map.entry("last_remembrance", "let go / the court remembers"),
      java.util.Map.entry("voidfang", "rift slash"),
      java.util.Map.entry("starfall", "charged starfall"),
      java.util.Map.entry("enderheart", "gravity break / enderfall"),
      // The awakened tier is the same weapon on shorter clocks, so it claims the same
      // right-click. Declared separately because the coverage check keys on the *type*,
      // and a forged upgrade is a different type name with the same behaviour.
      java.util.Map.entry("voidfang_awakened", "rift slash (awakened)"),
      java.util.Map.entry("starfall_awakened", "charged starfall (awakened)"),
      java.util.Map.entry("enderheart_awakened", "gravity break / enderfall (awakened)"),
      java.util.Map.entry("rune_swiftness", "apply rune of swiftness"),
      java.util.Map.entry("rune_frost", "apply rune of frost"),
      java.util.Map.entry("rune_reach", "apply rune of reach"),
      java.util.Map.entry("rune_lifesteal", "apply rune of lifesteal"),
      java.util.Map.entry("rune_warding", "apply rune of warding"),
      java.util.Map.entry("rune_fortitude", "apply rune of fortitude"),
      // The sea and the sky: two summons, two boxes, and five of the six legendaries.
      java.util.Map.entry("sovereigns_heart", "drowned sovereign summon"),
      java.util.Map.entry("drowned_loot_box", "drowned sovereign loot box spin"),
      java.util.Map.entry("leviathans_grasp", "grasp / crush / undertow"),
      java.util.Map.entry("tidecaller", "send the wave / undertow"),
      java.util.Map.entry("abyssal_chain", "chain pull / abyssal hook / undertow"),
      java.util.Map.entry("gale_sigil", "gale warden summon"),
      java.util.Map.entry("gale_loot_box", "gale warden loot box spin"),
      java.util.Map.entry("skybreaker", "wind slash / break the sky"),
      java.util.Map.entry("gale_chakram", "throw the chakram")
   );

   /**
    * Custom item types that intentionally have no right-click behaviour - worn
    * armour, held weapons, materials, placeable machines and signs.
    *
    * <p>The self-test requires every item type in the {@code /ff give} catalog to
    * be in exactly one of these two tables. A brand-new item that is neither
    * handled nor explicitly declared passive therefore fails the build instead of
    * quietly shipping as a dead item that does nothing when right-clicked.
    */
   private static final java.util.Set<String> PASSIVE_TYPES = java.util.Set.of(
      "auto_sell_hopper", "chair", "chunk_claimer", "elevator", "item_forge",
      "spawner_infuser", "token_redeemer", "upwards_hopper", "spawner_loot",
      // The eight late-game machines. Seven of them are placeable blocks whose right-click happens
      // at the block rather than in the hand (the click that matters to a furnace is the one on the
      // furnace), and the eighth - the Potion Belt - is handled below, because its click is the
      // whole item.
      "chunk_anchor", "repair_station", "item_sorter", "portable_furnace", "portable_campfire",
      "auto_planter", "auto_harvester", "irrigation_sprinkler",
      // The heavy hoppers and the fast furnace, for the same reason as the machines above: their
      // right-click happens at the block, not in the hand.
      "super_hopper", "transfer_hopper", "overflow_hopper", "checker_hopper", "two_way_splitter",
      "super_smelter",
      // The Sorter Tag is the machines' shape, not a click-in-hand item: its verb is a right-click
      // on a *block* (the container it names), so the bare-hold probe has nothing to resolve and
      // declaring it passive is the honest answer. It is not in USE_HANDLERS because no in-hand
      // click reaches it - see the block branch in onUseBlock.
      "sorter_tag",
      // Clicking neither does nor should do anything: the axe's Warlord's Rage is
      // an on-hit/tick ability, and the upgrader is an Item Forge material. Both
      // used to sit in USE_HANDLERS, claiming a right-click they never had, which
      // is exactly how a "handled" item hides a dead click - the self-test's
      // use probe is what caught them.
      "warlord_axe", "raiders_item_upgrader",
      "distant_memory_shard", "distant_memory_sword", "ender_pouch", "frozen_heart", "golem_core", "golem_trophy",
      "king_bone", "repair_membrane", "sculk_essence", "shattered_mind", "slime_core", "slime_shield",
      // The two newest forge materials. Like every material before them they are upgraded *with*
      // rather than used, so a click that does nothing is correct - and declaring them here is
      // what stops them shipping as a dead click, which the use probe exists to catch.
      "abyssal_pearl", "gale_core",
      "slime_trophy", "stoneheart", "warlord_cloak", "warlord_trophy", "wither_blade", "wither_crown",
      "evoker_cloak", "illusioner_cloak", "glacier_cloak",
      // Scarlet Devil materials and her fang: the fang's lifesteal is an on-hit
      // ability, so a right-click on it does - and should do - nothing.
      "scarlet_trophy", "bloodsoaked_core", "scarlets_fang",
      // The Clockwork King's trophy, forge material and three legendaries: all
      // weapons, armour and materials. None of them has a right-click, and
      // declaring them here is what stops any of them shipping as a dead click.
      "clockwork_trophy", "mech_scrap", "clockwork_gauntlet", "mechanical_heart", "automaton_armor",
      // The Ender Dragon's two materials. The three weapons made from them are all
      // handled (the rift, the charged shot, the two mace abilities), so they are NOT
      // listed here - and a material with a right-click that does nothing would be a
      // dead click, which is exactly what this table exists to catch.
      "heart_of_the_end", "dragon_scale",
      // The Magister's trophy, material and two on-hit/on-wear legendaries; the
      // Codex is handled (it casts), and her Compass and box are handled too.
      "starbound_trophy", "magical_essence", "starpiercer", "astral_mantle",
      // The Void Shaper's trophy, material and two worn/on-hit legendaries; the
      // Sigil is handled (it throws), and his Anchor and box are handled too.
      "colossus_trophy", "voidsteel_scrap", "void_reaver", "colossus_plate",
      // The Sovereign's trophy and material; his three legendaries are all
      // handled (contract, bell, seal), so they are NOT listed here.
      "sovereign_trophy", "royal_tribute",
      // Excalibur's whole kit is worn/held: ExcaliburTickMixin grants the 255
      // Strength and Sharpness while it is in hand, so a right-click on it does -
      // and should do - nothing. Declaring it here is what stops it shipping as
      // a dead click, which the use probe caught it doing.
      "excalibur",
      // The Puppeteer's two masks are worn, and one of them is a helmet first and
      // a trick second: a right-click on either does - and should do - nothing.
      // His mask's puppet and the Empty Mask's decoy both fire off kills and
      // damage, not off a click.
      "puppeteers_mask", "empty_mask",
      // The Warden's Mantle is the same shape as those two, only more so: it is a chestplate whose
      // entire kit is mobility, so its click is the click that puts it on. Declaring it here is
      // what stops "the chestplate does nothing" from reading as a bug - its four abilities are
      // real and they are all in the tick loop (see SeaAndSkyGear).
      "wardens_mantle"
      // The monarch's greatsword USED to be listed here, on the reasoning that it was a held
      // weapon whose click meant nothing. It is a handled type now - the blade performs the
      // castle's own rescue on a right-click and one remembered arc on a sneak (see
      // LastRemembrance) - so it belongs in USE_HANDLERS, and leaving it here as well would
      // leave the two tables disagreeing about the same item.
   );

   /**
    * Handled types whose handler deliberately hands the click back to vanilla by
    * returning {@code PASS} on Java: the two bows. Right-click on a bow is the
    * draw, which has to stay vanilla (and the client owns it), so "PASS here" is
    * the intended answer rather than a dead item.
    *
    * <p>The self-test's use probe requires every item it clicks to either resolve
    * the click, be listed as passive, or be listed here - so an item can only pass
    * by declaring that it means to do nothing, never by accident.
    */
   private static final java.util.Set<String> DEFER_TYPES = java.util.Set.of(
      "frostbound_crown", "slime_launcher"
   );

   /**
    * Real custom types that can never appear in the {@code /ff give} catalog, so
    * the self-test's "no stale entry" check must not demand a catalog item for
    * them. {@code sculk_orb} is created by the Sculk Mage Staff's ability;
    * {@code slime_trophy} / {@code golem_trophy} are handed out by their boss at
    * the moment of death; {@code spawner_loot} is a tag applied to a dropped item
    * rather than an item of its own.
    */
   private static final java.util.Set<String> NON_CATALOG_TYPES = java.util.Set.of(
      "sculk_orb", "slime_trophy", "golem_trophy", "spawner_loot"
   );

   /** Types that exist but cannot be handed out - see {@link #NON_CATALOG_TYPES}. */
   public static java.util.Set<String> nonCatalogTypes() {
      return NON_CATALOG_TYPES;
   }

   /**
    * Every {@code ModItems.is*} predicate the {@link #onUseItem} chain tests.
    *
    * <p>This is the anti-drift half of the item coverage check: the
    * {@code ffAuditSources} gate compares this set against the predicates actually
    * used inside {@code onUseItem} and fails on any difference. Adding a branch to
    * the dispatch chain therefore forces a matching entry here - and this list
    * lives right beside {@link #USE_HANDLERS} / {@link #PASSIVE_TYPES}, so the new
    * item's type has to be classified at the same time.
    */
   private static final java.util.Set<String> USE_PREDICATES = java.util.Set.of(
      "isBackpack", "isBountyCompass", "isBundle", "isBuySign", "isCaptainHorn", "isChronoShard", "isDeathCompass",
      "isEnderBackpack", "isEvokerSpellbook", "isFrostboundCrown", "isGolemFist", "isGolemLootBox", "isHourglass",
      "isIceStaff", "isIllusionerSpellbook", "isKingLootBox", "isMindAscended", "isMindLootBox", "isMindbinderEye",
      "isMindbinderShroud", "isMindbinderStaff", "isMysteryBox", "isPocketWatch", "isPossessedMask", "isRaidBanner",
      "isRaidBossToken", "isRaidLootBox", "isRune", "isSculkFood", "isSculkLootBox", "isSculkMageStaff",
      "isSculkMedallion", "isSculkOrb", "isSlimeBoots", "isSlimeBossToken", "isSlimeLauncher",
      "isSellSign", "isSlimeLootBox", "isSnowLootBox", "isSnowQueenToken", "isSpaceTimeRift", "isStoneGolemToken", "isStoneStaff",
      "isExpeditionCompass",
      "isTimeLordLootBox", "isWardensCall", "isWitherCloakSword", "isWitherLootBox", "isWitherStaff", "isWormholePotion",
      "isScarletBlood", "isScarletLootBox", "isScarletGrimoire", "isBloodPrism",
      "isClockworkCore", "isClockworkLootBox",
      "isAstralCompass", "isStarboundLootBox", "isMagistersCodex",
      "isVoidAnchor", "isVoidshaperLootBox", "isShapingSigil",
      "isSovereignsCrown", "isSovereignLootBox", "isRoyalContract",
      "isSovereignsBell", "isEmeraldSeal",
      "isWoodenMarionette", "isMarionetteStrings", "isPuppeteerLootBox",
      "isLastRemembrance",
      "isAnyVoidfang", "isAnyStarfall", "isAnyEnderheart",
      "isSovereignsHeart", "isGaleSigil", "isDrownedLootBox", "isGaleLootBox",
      "isLeviathansGrasp", "isTidecaller", "isAbyssalChain", "isSkybreaker", "isGaleChakram",
      "isPotionBelt"
   );

   /** The right-click handler registered for this stack's type, or {@code null}
    *  when it has none. Read by the self-test's item coverage check. */
   public static String useHandlerFor(ItemStack stack) {
      String type = stack == null || stack.isEmpty() ? null : ModItems.typeOf(stack);
      return type == null ? null : USE_HANDLERS.get(type);
   }

   /** A snapshot of the dispatch table, for the self-test's coverage report. */
   public static java.util.Set<String> handledTypes() {
      return USE_HANDLERS.keySet();
   }

   /** A snapshot of the deliberately-passive types, for the self-test. */
   public static java.util.Set<String> passiveTypes() {
      return PASSIVE_TYPES;
   }

   /** A snapshot of the handlers that intentionally defer to vanilla, for the
    *  self-test. See {@link #DEFER_TYPES}. */
   public static java.util.Set<String> deferTypes() {
      return DEFER_TYPES;
   }

   private static InteractionResult onAttack(Player player, Level level, Entity entity) {
      if (!level.isClientSide() && player instanceof ServerPlayer sp) {
         // On the shared clock, so the stamp is comparable with the read in BossManager's
         // takeover tick even when the click happened inside a realm whose game time is frozen.
         BossManager.registerTakeoverClick(sp, com.fortuneandfavors.economy.ServerClock.clock(level));
         if (DuelManager.isSpectating(sp.getUUID())) {
            return InteractionResult.FAIL;
         }

         // A stopped player cannot act - that is the whole point of the Time
         // Lord's time stop and of a Pocket-Watch catching you.
         if (com.fortuneandfavors.economy.TimeLordManager.isTimeStopped(sp)) {
            return InteractionResult.FAIL;
         }

         // Neither can a body somebody else is wearing. The Puppeteer's Final Knot takes
         // the whole player, and "cannot do anything" has to be true at the door their
         // actions actually come through: the body swings because the mod swings it, and
         // the player's own clicks are refused here. Told on the action bar rather than in
         // chat, because a held mouse button would be a message every half second.
         if (com.fortuneandfavors.economy.PuppeteerManager.isPossessed(sp)) {
            sp.sendOverlayMessage(
               net.minecraft.network.chat.Component.literal(
                  "\u00a75\u2726 \u00a7fYour hand is not your own \u00a78- \u00a7dkill him to get it back."
               )
            );
            return InteractionResult.FAIL;
         }

         // Anticheat (off unless an admin switched it on): a swing outside the
         // reach the server would have accepted on its own, or one landed on
         // something the attacker was not facing, never lands.
         if (!com.fortuneandfavors.anticheat.AntiCheat.onAttack(sp, entity)) {
            return InteractionResult.FAIL;
         }

         if (entity instanceof LivingEntity le) {
            Safe.run("rune flame", () -> RuneManager.onAttack(sp, le));
         }

         ItemStack headStack = sp.getMainHandItem();
         if (DuelManager.isGoldenAppleHead(headStack) && DuelManager.isGoldenAppleHeadAllowed(sp) && DuelManager.eatGoldenAppleHead(sp, headStack)) {
            return InteractionResult.SUCCESS;
         }

         if (DuelManager.onPlayerAttackBot(sp, entity)) {
            return InteractionResult.SUCCESS;
         }

         if (BossManager.isSealed(sp.getUUID()) && level instanceof ServerLevel sl) {
            BossManager.onPrisonSwing(sl, sp);
         }

         if (BossManager.isMindControlled(sp.getUUID())) {
            LivingEntity victim = BossManager.mindPuppetVictim(sp);
            if (victim != null && victim != entity) {
               if (victim instanceof ServerPlayer vp && !ClaimManager.pvpAllowed(sp, vp)) {
                  return InteractionResult.FAIL;
               }

               long now = level.getGameTime();
               if (ARM_MSG_COOLDOWN.getOrDefault(sp.getUUID(), 0L) <= now) {
                  ARM_MSG_COOLDOWN.put(sp.getUUID(), now + 40L);
                  Chat.msg(sp, "&5Your arm moves on its own...");
               }

               ServerLevel sl = (ServerLevel)level;
               victim.hurtServer(sl, sl.damageSources().playerAttack(sp), BossManager.weaponDamage(sl, sp, sp.getMainHandItem(), victim));
               return InteractionResult.FAIL;
            }
         }

         if (BossManager.tryLauncherLeftClick(sp)) {
            return InteractionResult.SUCCESS;
         }

         if (isFrameLike(entity)) {
            if (!ClaimManager.canBuild(sp, entity.blockPosition())) {
               Chat.msg(sp, "&cYou can't touch that - it's in claimed land.");
               return InteractionResult.FAIL;
            } else {
               return InteractionResult.PASS;
            }
         } else if (entity instanceof ServerPlayer victim && !ClaimManager.pvpAllowed(sp, victim)
               && !PlayerRaidManager.isActiveBetrayer(victim.getUUID())) {
            Chat.msg(sp, "&cThis land has PvP disabled - you can't fight here.");
            return InteractionResult.FAIL;
         } else {
            return InteractionResult.PASS;
         }
      } else {
         return InteractionResult.PASS;
      }
   }

   /**
    * The name on a Sorter Tag, if the player renamed one: that name is the tag's id.
    *
    * <p>The item itself is recognised by type, not by its default name, so a player who leaves the
    * name as "Sorter Tag" does not claim that as a tag id - the mod hands out the next short
    * {@code C1}, {@code C2} for them instead. See {@link com.fortuneandfavors.economy.SorterLinks}.
    */
   private static String heldTagName(ItemStack stack) {
      net.minecraft.network.chat.Component name = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
      if (name == null) {
         return null;
      }
      String text = name.getString();
      // A fresh tag wears this mod's own name, and that is not a name a player chose: leaving it
      // alone must hand out a short C1/C2 rather than claim "3lSorter_Tag" as an id.
      return ModItems.SORTER_TAG_DEFAULT_NAME.equals(text) ? null : text;
   }

   private static void onBlockBroken(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
      if (!level.isClientSide()) {
         MapEditor.onBlockBroken(level, pos);
         // A tagged container that is gone stops being tagged: the tag is a name for a place, and the
         // place is gone. Sorters keep the name they were pointed at - tagging its replacement picks
         // the line straight back up - but nothing should quietly keep reaching at a hole.
         if (level instanceof ServerLevel tagged) {
            Safe.run("sorter tag dropped", () -> com.fortuneandfavors.economy.SorterLinks.onContainerBroken(tagged, pos));
         }
         if (player instanceof ServerPlayer sp) {
            // A stopped player cannot mine either.
            if (com.fortuneandfavors.economy.TimeLordManager.isTimeStopped(sp)) {
               return;
            }
            Safe.run("nicekeepinv track break", () -> NiceKeepInventoryManager.setLastTouched(sp, pos));
            if (level instanceof ServerLevel sl) {
               DuelManager.onDuelBlockBroken(sl, pos, sp);
               if (DuelManager.isBedwarsBed(sl, pos)) {
                  for (ItemEntity e : sl.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0))) {
                     e.discard();
                  }
               }
            }

            Safe.run("ore discovery", () -> ShopProgression.trackOreDiscovery(sp, state));
            JobManager.onBlockBroken(sp, state);
            if (level instanceof ServerLevel serverLevel) {
               SkillManager.onBlockBroken(sp, state, serverLevel, pos);
               Safe.run("mining pity", () -> MiningPity.onBlockBroken(serverLevel, sp, pos, state));
               Safe.run("challenge block progress", () -> DailyWeeklyChallengeManager.onBlockBroken(sp, state));
               Safe.run("expedition loot", () -> ExpeditionManager.onBlockBroken(sp, pos, state));
               Safe.run("prison cell block", () -> {
                  if (PrisonManager.isInPrison(sp)) {
                     PrisonManager.onBlockMined(sp, pos, state);
                  }
               });
               Safe.run("dynamic ore job", () -> DynamicContractsManager.onBlockBroken(sp, state));
               Safe.run("mining zone bonus", () -> MiningZoneManager.onBlockBroken(serverLevel, pos, sp, state));
               // Retirement, not a query: every consumer of this break above has had
               // its answer on the record, so the placement record for this position is
               // spent. It sits here - after the last asker - so the excavator, mining
               // pity and the mining zones can never disagree about whether the same
               // block was placed by a player.
               Safe.run("natural block bookkeeping", () -> com.fortuneandfavors.economy.NaturalBlocks.onBlockBroken(serverLevel, pos));
            }

            if (state.is(Blocks.SPAWNER)) {
               SpawnerManager.onMined(level, pos, sp, blockEntity);
            }

            if (level instanceof ServerLevel serverLevel) {
               if (BossManager.isPrisonBlock(pos)) {
                  BossManager.onPrisonBlockMined(serverLevel, pos);
               }

               if (BossManager.isIceCubeDeposit(pos)) {
                  BossManager.onIceCubeDepositMined(serverLevel, pos, sp);
               }
            }
         }
      }
   }

   /** True when the hitter is swinging the Clockwork Gauntlet in either hand. */
   private static boolean attackerHeldGauntlet(ServerPlayer hitter) {
      return ModItems.isClockworkGauntlet(hitter.getMainHandItem()) || ModItems.isClockworkGauntlet(hitter.getOffhandItem());
   }

   private static ServerPlayer playerAttackerOf(DamageSource source) {
      if (source.getEntity() instanceof ServerPlayer p) {
         return p;
      } else {
         return source.getDirectEntity() instanceof Projectile pr && pr.getOwner() instanceof ServerPlayer p ? p : null;
      }
   }

   private static String ownerName(Level level, UUID uuid) {
      MinecraftServer server = level.getServer();
      if (server != null) {
         ServerPlayer p = server.getPlayerList().getPlayer(uuid);
         if (p != null) {
            return p.getName().getString();
         }
      }

      return "another player";
   }

   /** Grant the all_runes achievement when a weapon now holds one of every
    *  weapon rune type (armor runes can't fit weapons). Cheap check on every
    *  socket - fine at socket frequency. */
   public static void grantAllRunes(ServerPlayer sp, ItemStack item) {
      if (RuneManager.isWeapon(item)) {
         for (String t : RuneManager.weaponTypes()) {
            if (!ModItems.hasRune(item, t)) {
               return;
            }
         }
         com.fortuneandfavors.economy.Advancements.grant(sp, "all_runes");
      } else if (RuneManager.isArmor(item)) {
         for (String t : RuneManager.armorTypes()) {
            if (!ModItems.hasRune(item, t)) {
               return;
            }
         }
         com.fortuneandfavors.economy.Advancements.grant(sp, "all_armor_runes");
      }
   }

   private static void goldenAppleNerfTick(ServerPlayer player) {
      if (++goldenAppleNerfTick % 20L == 0L) {
         if (DuelManager.isInDuel(player.getUUID())) {
            if (!DuelManager.isLegacyFight(player)) {
               if (!DuelManager.isUhcDuel(player)) {
                  MobEffectInstance regen = player.getEffect(MobEffects.REGENERATION);
                  if (regen != null && regen.getAmplifier() == 1 && regen.getDuration() > 0 && regen.getDuration() <= 120) {
                     player.removeEffect(MobEffects.REGENERATION);
                     player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0, false, false));
                  }

                  MobEffectInstance absorb = player.getEffect(MobEffects.ABSORPTION);
                  if (absorb != null && absorb.getAmplifier() == 1 && absorb.getDuration() > 0 && absorb.getDuration() <= 2000) {
                     player.removeEffect(MobEffects.ABSORPTION);
                     player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0, false, false));
                  }
               }
            }
         }
      }
   }
}
