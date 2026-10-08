# Graph Report - Fortune-Favors  (2026-10-08)

## Corpus Check
- Large corpus: 1309 files · ~1,671,626 words. Semantic extraction will be expensive (many Claude tokens). Consider running on a subfolder.

## Summary
- 11788 nodes · 50770 edges · 248 communities (134 shown, 114 thin omitted)
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 324 edges (avg confidence: 0.85)
- Token cost: 58,099 input · 0 output

## Community Hubs (Navigation)
- Economy Core & Reflection
- Command Registration
- Item Stacks & Special Loot
- Player Admin & Moderation
- Heists & Wanted Heat
- Boss Spawning & Bodies
- Cuffs & Executions
- Vanilla Type References A
- Stone Golem Tuning
- Menu Classes Index
- Auctions & Persistence Types
- Save Data & Serialization
- Expedition Rolls & Reports
- Combat Event Hooks
- Guild System
- Boss Grounding & World Clock
- Wagers & Spectator Bets
- Anticheat Core
- Duels & Bedwars Shop
- Custom Enchantments & Anvil
- Dungeon Arenas & Bodies
- Duel Bot AI
- Ender Dragon Manager
- Auction House
- Network Packet Mixins
- Duel Sessions & Spectating
- Server Disasters & Meteors
- Gale Warden Fight
- Client Packet Types
- Void Shaper Gear
- Duel Bot Construction
- Drowned Sovereign Boss
- Ender Gear & Astral Dive
- Registry Entries & Binding
- Mob Spawning & Chunks
- Contracts & Downed State
- Clockwork King & Boss Chat
- Elevator Blocks
- Anticheat Movement Checks
- QoL Compat Modes
- Ender Dragon Ascension
- Expedition Chest Ledger
- Anticheat Exemptions
- Duel Challenges & Leaderboard
- Dungeon Chamber Types
- Item Mixins (MixinExtras)
- Boss Move Sequencing
- Rarity Bands
- Boss Texture Generator
- Display Entity VFX & Chakram
- Backpack Kitchen & Discs
- Hopper Machines
- Market Categories & Prices
- Expedition Progression
- Advanced Enchantments
- Lethal Damage Mixins
- Command Selector Mixins
- Skills & RNG
- Daily Login Streak
- Bounty Compass
- Emerald Sovereign Boss
- Bedrock Music
- Client Config (AutoConfig)
- Stock Market Manager
- Client HUD Mixins
- Fusion Particle Effects
- Death Compass
- Dungeon Loot Boards
- Wither Bolt Fight
- Excalibur Texture Script
- Bank Accounts
- Bedrock Setup Docs
- Entity AI Mixins
- Text Displays & Cooldowns
- Boss Arrivals & Revenants
- Soulbound & Sell Values
- Lottery
- Ender Weapons Texture Script
- Spawner Manager
- Ender Forging
- Map Plots & Presets
- Client Interaction Mixins
- Projectile & NBT Hooks
- Anticheat Enforcement
- Restore & Commands Bridge
- Duel Plot Cleanup
- Boss Empowerment
- Wither Rework
- Time Lord Texture Script
- Client Ground-Slam Animations
- Leaderboard Categories
- Mirage Castle Boss
- Arena Guardian Moves
- Jobs
- Potion Belt
- Warden Entity Hooks
- Wither Fight Phases
- Trading Shop Hoppers
- Sea & Sky Texture Script
- Duel Arenas
- Machine Tuning
- Golden Apple Heads
- Docs: Features & Changelog
- Anticheat Container Clicks
- Chunk Anchors
- Market View Menu
- Boss Bar Texture Script
- Chest Shops
- Runes
- Player Raids
- Farm Machines
- Anticheat Aim Analysis
- Raid Gear & Illusions
- Skill Upgrade Trees
- Chairs
- Structure Footprints
- Anvil Prompt Menu
- Expedition Guide Menu
- Feature Config Menu
- Enderheart Texture Script
- Daily & Weekly Challenges
- Client Movement Packets
- Backpack Music Client
- Backpack Jukebox
- Market Index Series
- Server Events
- Magister Gear
- Puppeteer Gear
- Scarlet Gear & Rituals
- Scoreboard Manager
- Ender Dragon Texture Script
- Heavy Hoppers
- Lag-Compensated Hit Checks
- Container & Door Interaction
- Chest Shop Menu
- Fix Textures Script
- Mixin Conditional Wraps
- Command Argument Types
- VFX Texture Kinds
- Bet & Combat Style
- Boss Codex
- Cosmetics
- VFX Client Payloads
- Performance Monitor
- Credits & End Music
- Codex Pages
- Raid Wave Logic
- Claim Menu Items
- Anticheat Menu
- Expedition Collapse
- Bot Profiles
- Chest Shop Config Menu
- Gems
- Crystal Kinds
- Supplier Loot
- Keep Inventory & Graves
- Mining Zones
- Raid Betrayal
- Raid Boss Help Menu
- Villager Trades
- Draft Mode
- Expedition Board
- Puppeteer Texture Script
- Persistence Recovery
- Scoreboard Lines
- Sovereign Guards & Seal
- Shop Ledger Menu
- Missing Item Texture Script
- Deliver Menu
- Armor Render Layers
- Client Music Fades
- World Backups
- Mystery Box Menu
- Sell Menu
- Python Script Utilities
- Reactive Zombie Kits
- Bot Difficulty Menu
- Spectator Mode
- Boss Phases
- VFX Manager
- Raid Texture Script
- Client Particles
- Scoreboard Panels
- Client Context Hints
- Clockwork Gear
- Contract Progress
- Bundle Menu
- Boss VFX Budget
- Blood Boss Casts
- Tags
- Wither Test Driver
- Lucky PvP Menu
- Scoreboard Text Menu
- Records Menu
- Scoreboard Edit Menu
- Item List Menu
- Cooperative Achievements
- Price Tiers
- Collapse Waves
- Playtime
- Redeemer Menu
- Auto-Sell Menu
- Game Profile Properties
- Platform Helpers
- Duel Arena Probe
- Snowball Mixin
- Bedwars Upgrade Menu
- Death Drop Guard
- Bedwars Shop Menu
- Codex Menu
- Job Menu
- Kits Menu
- Recipe Menu
- Skywars Kit Menu
- Training Dummy Menu
- Claim Results
- World Anomalies
- Interaction Kinds
- Sonic Boom
- Cuff Menu
- Bot Hand Actions
- Boss Payout
- Alert Levels
- News Menu
- Mod Menu Integration
- Duel Phases
- Possessed Player
- Server Clock
- Bat Swarm
- Build Jar Script
- Mist Pool

## God Nodes (most connected - your core abstractions)
1. `BossManager` - 715 edges
2. `DuelManager` - 532 edges
3. `ModItems` - 522 edges
4. `ExpeditionManager` - 396 edges
5. `ModCommands` - 278 edges
6. `EnderDragonManager` - 263 edges
7. `PrisonCellblock` - 233 edges
8. `AntiCheat` - 219 edges
9. `PrisonManager` - 200 edges
10. `State` - 182 edges

## Surprising Connections (you probably didn't know these)
- `CombatGear per-player leak fix` --references--> `CombatGear`  [EXTRACTED]
  CHANGELOG.md → src/main/java/com/fortuneandfavors/economy/CombatGear.java
- `Boss music via record.* sounds` --references--> `BedrockMusic`  [EXTRACTED]
  BEDROCK-SETUP.md → src/main/java/com/fortuneandfavors/economy/BedrockMusic.java
- `Fortune-Favors` --conceptually_related_to--> `Changelog (CHANGELOG.md)`  [INFERRED]
  README.md → CHANGELOG.md
- `Bundled changelog.txt` --references--> `Changelog (CHANGELOG.md)`  [EXTRACTED]
  src/main/resources/data/fortuneandfavors/changelog.txt → CHANGELOG.md
- `/changelog command` --references--> `Bundled changelog.txt`  [EXTRACTED]
  CHANGELOG.md → src/main/resources/data/fortuneandfavors/changelog.txt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Bedrock client asset delivery** — tools_build_resourcepack, bedrock_setup_bedrock_mcpack, bedrock_setup_geyser_mappings, bedrock_setup_geyser, bedrock_setup_custom_item_art [EXTRACTED 1.00]
- **Heavy hopper machine family** — changelog_super_hopper, changelog_splitter, changelog_checker_hopper, changelog_overflow_hopper, changelog_transfer_hopper [INFERRED 0.85]

## Communities (248 total, 114 thin omitted)

### Community 0 - "Economy Core & Reflection"
Cohesion: 0.01
Nodes (20): BossManager, CorruptionHold, FistWave, GolemBlock, GolemCrumble, GooZone, HomingCharge, KingMove (+12 more)

### Community 1 - "Command Registration"
Cohesion: 0.03
Nodes (5): DuelCommands, PermissionManager, ModCommands, RaidBossRow, RecoveryResult

### Community 2 - "Item Stacks & Special Loot"
Cohesion: 0.03
Nodes (3): SculkOrb, SculkOrbProjectile, ModItems

### Community 3 - "Player Admin & Moderation"
Cohesion: 0.02
Nodes (10): SpectateKit, AfkManager, State, SculkFoodManager, VanishManager, AuctionDurationMenu, InfuserMenu, MenuHubMenu (+2 more)

### Community 4 - "Heists & Wanted Heat"
Cohesion: 0.02
Nodes (18): Momentum, Pit, PitMode, BOUNTY, CHAMPION, DUEL, GAUNTLET, WARDEN (+10 more)

### Community 5 - "Boss Spawning & Bodies"
Cohesion: 0.03
Nodes (11): ActiveBoss, BodyTakeover, FistBlock, GolemRock, SlimeHazard, SnowHit, SnowMist, SoulFire (+3 more)

### Community 6 - "Cuffs & Executions"
Cohesion: 0.02
Nodes (16): Cuffs, Escape, Execution, Lock, IDLE, LIT, OPENED, LooseBrick (+8 more)

### Community 8 - "Vanilla Type References A"
Cohesion: 0.02
Nodes (6): LeaderboardRow, SpectatorSession, ToolTier, EquipmentAttributes, SwordBlockManager, NaturalSpawnerMixin

### Community 9 - "Stone Golem Tuning"
Cohesion: 0.03
Nodes (16): Ability, ARROWS, NONE, SHIELD, TOTEM, Fight, MobTether, Possession (+8 more)

### Community 10 - "Menu Classes Index"
Cohesion: 0.13
Nodes (8): EconomyManager, LotteryMenu, ModSounds, Chat, GuiUtil, InventoryHelper, PriceUtil, SoundUtil

### Community 11 - "Auctions & Persistence Types"
Cohesion: 0.05
Nodes (6): Machine, GraveData, ChatCoalescer, LastBuy, JsonUtil, NkiSnapshotHolder

### Community 13 - "Expedition Rolls & Reports"
Cohesion: 0.03
Nodes (4): ArenaReport, ExpeditionManager, SquadRow, TypeStats

### Community 15 - "Guild System"
Cohesion: 0.04
Nodes (12): Guild, GuildManager, LeaveRequest, Mail, Rank, FOUNDER, MEMBER, OFFICER (+4 more)

### Community 16 - "Boss Grounding & World Clock"
Cohesion: 0.04
Nodes (10): BossGrounding, ChronoFx, Aging, Bomb, Deferred, Fight, Moment, Pin (+2 more)

### Community 17 - "Wagers & Spectator Bets"
Cohesion: 0.03
Nodes (10): RepairStation, Entry, Flow, Trade, TradeManager, MineDepositPromptMenu, RedeemerPayoutMenu, RepairStationMenu (+2 more)

### Community 18 - "Anticheat Core"
Cohesion: 0.03
Nodes (3): AntiCheat, BreakClaim, HackLine

### Community 19 - "Duels & Bedwars Shop"
Cohesion: 0.03
Nodes (5): BwShopEntry, DuelManager, WagerRequest, WagerStaged, BlockSetPlacedByMixin

### Community 20 - "Custom Enchantments & Anvil"
Cohesion: 0.04
Nodes (7): CCEnchantments, ChainfireShoot, QueuedChainShot, CustomEnchantments, ItemForgeMenu, LockedSlot, AnvilMixin

### Community 21 - "Dungeon Arenas & Bodies"
Cohesion: 0.06
Nodes (4): Arena, Body, Room, State

### Community 22 - "Duel Bot AI"
Cohesion: 0.04
Nodes (10): DuelBot, BotBlow, BotDifficulty, EASY, HACKER, HARD, NORMAL, TRAIN (+2 more)

### Community 24 - "Auction House"
Cohesion: 0.05
Nodes (23): Auction, AuctionManager, Category, ALL, BLOCKS, FOOD, GEAR, MACHINES (+15 more)

### Community 25 - "Network Packet Mixins"
Cohesion: 0.05
Nodes (16): AdvancedProjectileMixin, ChatGarblerMixin, DuelCommandGuardMixin, EnchantmentXpMixin, LauncherClickMixin, MobMixin, NaturalRegenMixin, PlayerAttackMixin (+8 more)

### Community 26 - "Duel Sessions & Spectating"
Cohesion: 0.05
Nodes (3): Duel, FfaBoard, Participant

### Community 27 - "Server Disasters & Meteors"
Cohesion: 0.04
Nodes (3): CoverKey, Meteor, ServerDisasterManager

### Community 28 - "Gale Warden Fight"
Cohesion: 0.07
Nodes (3): Fight, GaleWardenManager, Pending

### Community 29 - "Client Packet Types"
Cohesion: 0.03
Nodes (4): Advancements, BlockValues, ElytraLunge, FortuneFavorsMod

### Community 30 - "Void Shaper Gear"
Cohesion: 0.06
Nodes (8): VoidShaperGear, Fight, Held, Kind, Lift, Loose, Shot, VoidShaperManager

### Community 31 - "Duel Bot Construction"
Cohesion: 0.05
Nodes (5): ServerGamePacketListenerImpl, KindApi, SelfTest, Toggle, RegistryFingerprint

### Community 32 - "Drowned Sovereign Boss"
Cohesion: 0.06
Nodes (3): DrownedSovereignManager, Fight, Pending

### Community 33 - "Ender Gear & Astral Dive"
Cohesion: 0.04
Nodes (8): Astral, Dive, EnderGear, Marks, Rise, Star, Weightless, LastRemembrance

### Community 34 - "Registry Entries & Binding"
Cohesion: 0.07
Nodes (3): Entry, ItemSorter, ItemSorterMenu

### Community 35 - "Mob Spawning & Chunks"
Cohesion: 0.06
Nodes (5): Claim, ClaimManager, ClaimMenu, AddPlayerMenu, ClaimPermsMenu

### Community 36 - "Contracts & Downed State"
Cohesion: 0.07
Nodes (5): Contract, Downed, DynamicContractsManager, Target, WitherWatch

### Community 37 - "Clockwork King & Boss Chat"
Cohesion: 0.06
Nodes (9): BossChat, ClockworkKingManager, Fight, Plate, Role, BLADE, DRONE, PISTON (+1 more)

### Community 38 - "Elevator Blocks"
Cohesion: 0.04
Nodes (6): ElevatorBlock, SpawnAnim, TempBlock, MiningPity, NaturalBlocks, LastSpot

### Community 39 - "Anticheat Movement Checks"
Cohesion: 0.07
Nodes (4): Track, MotionModel, Tick, MovementPhysics

### Community 40 - "QoL Compat Modes"
Cohesion: 0.06
Nodes (3): Mode, QolCompat, ModConfig

### Community 42 - "Expedition Chest Ledger"
Cohesion: 0.08
Nodes (3): Party, PartyManager, PartyMenu

### Community 43 - "Anticheat Exemptions"
Cohesion: 0.04
Nodes (6): AntiCheatStore, Exemption, Punishment, Record, Review, Spectate

### Community 44 - "Duel Challenges & Leaderboard"
Cohesion: 0.05
Nodes (25): Challenge, DuelMode, ARCHERY, AXEDUEL, BEDWARS, COMBODUEL, CRYSTALPVP, DRAFT (+17 more)

### Community 45 - "Dungeon Chamber Types"
Cohesion: 0.03
Nodes (51): Chamber, ALCHEMY, ARENA, ARMOURY, BATHHOUSE, BROKEN_CROSSING, CACHE, CALM_CAMP (+43 more)

### Community 46 - "Item Mixins (MixinExtras)"
Cohesion: 0.06
Nodes (20): CombatBalanceMixin, CrossbowMixin, CrossbowRevolverMixin, CubeMobMixin, DispenserBlockMixin, DragonEggBlockMixin, EvokerFangsMixin, ExcaliburTickMixin (+12 more)

### Community 47 - "Boss Move Sequencing"
Cohesion: 0.03
Nodes (58): Move, ABYSSAL_ROAR, ASCENDANT_BLINK, AURA_OF_THE_END, BREATH_LANCE, BREATH_NOVA, CRYSTAL_BLOOM, CRYSTAL_RESONANCE (+50 more)

### Community 48 - "Rarity Bands"
Cohesion: 0.08
Nodes (7): Band, COMMON, EPIC, LEGENDARY, RARE, LootBoxMenu, Prize

### Community 49 - "Boss Texture Generator"
Cohesion: 0.06
Nodes (35): astral_compass(), astral_mantle(), automaton_armor(), canvas(), chest(), clockwork_core(), clockwork_gauntlet(), clockwork_loot_box() (+27 more)

### Community 50 - "Display Entity VFX & Chakram"
Cohesion: 0.08
Nodes (5): Chakram, SeaAndSkyGear, SkySlam, Vortex, Wave

### Community 51 - "Backpack Kitchen & Discs"
Cohesion: 0.06
Nodes (3): BackpackKitchen, BackpackMenu, PortableCraftingMenu

### Community 52 - "Hopper Machines"
Cohesion: 0.10
Nodes (4): Machine, MachineManager, HopperBlockEntityMixin, HopperLootMixin

### Community 53 - "Market Categories & Prices"
Cohesion: 0.07
Nodes (10): Category, BUILDING, EXCLUSIVE, FOOD, REDSTONE, TOOLS, ShopData, ShopEntry (+2 more)

### Community 54 - "Expedition Progression"
Cohesion: 0.06
Nodes (12): ExpeditionProgression, Progress, Upgrade, BROKER, DEEP_POCKETS, FIELD_KIT, FIELD_MEDICINE, LONGER_ROPE (+4 more)

### Community 56 - "Lethal Damage Mixins"
Cohesion: 0.06
Nodes (15): Answer, CLAIM, DEFER, INNOCENT, Blow, Handler, LethalBlows, Rule (+7 more)

### Community 57 - "Command Selector Mixins"
Cohesion: 0.05
Nodes (14): HashedPatchMapMixin, ArrowEnchantMixin, ArrowMixin, BeaconPaymentMixin, BetterSleepMixin, EntitySelectorMixin, LivingEntityMixin, MinecraftServerMixin (+6 more)

### Community 59 - "Daily Login Streak"
Cohesion: 0.06
Nodes (9): DailyLoginStreakManager, PlayerStreak, RewardPlan, Shift, MysteryChestManager, DailyRewardsMenu, FirstJoinMenu, MysteryChestMenu (+1 more)

### Community 60 - "Bounty Compass"
Cohesion: 0.06
Nodes (7): BountyCompassManager, Bounty, BountyManager, DisplayPrefsManager, TitleManager, BountyMenu, TagsMenu

### Community 61 - "Emerald Sovereign Boss"
Cohesion: 0.09
Nodes (4): Emerald, EmeraldSovereignManager, Fight, Mark

### Community 62 - "Bedrock Music"
Cohesion: 0.06
Nodes (7): BedrockMusic, StandIn, BossMusic, EnderCreditsMusic, EndIntroMusic, Session, WitherMusic

### Community 63 - "Client Config (AutoConfig)"
Cohesion: 0.06
Nodes (16): ClothBridge, BossDespawn, FIGHTERS, NEVER, SUMMONER, ClientCategory, FfClientConfig, ServerCategory (+8 more)

### Community 64 - "Stock Market Manager"
Cohesion: 0.10
Nodes (5): MarketManager, Position, Receipt, Stock, Wealth

### Community 65 - "Client HUD Mixins"
Cohesion: 0.07
Nodes (7): BossHealthOverlayMixin, HudInvoker, HudMixin, LocalPlayerMixin, LootBoxScreenMixin, ScreenFx, BaseSpawnerMixin

### Community 66 - "Fusion Particle Effects"
Cohesion: 0.08
Nodes (6): Fusion, EnderGaze, EndRift, Rupture, Tear, Volley

### Community 67 - "Death Compass"
Cohesion: 0.08
Nodes (5): DeathCompassManager, LastDeath, Waypoint, WormholeManager, WormholeMenu

### Community 68 - "Dungeon Loot Boards"
Cohesion: 0.08
Nodes (3): LootBackpack, LootBackpackMenu, LootChestMenu

### Community 69 - "Wither Bolt Fight"
Cohesion: 0.09
Nodes (4): Bolt, Fight, Mark, StarboundMagisterManager

### Community 70 - "Excalibur Texture Script"
Cohesion: 0.07
Nodes (26): disc(), excalibur(), main(), paint_line(), seg(), glow(), main(), rays() (+18 more)

### Community 71 - "Bank Accounts"
Cohesion: 0.10
Nodes (6): Account, BankManager, CatchUp, Growth, Movement, BankMenu

### Community 72 - "Bedrock Setup Docs"
Cohesion: 0.06
Nodes (29): Bedrock (Geyser) setup, fortuneandfavors-bedrock.mcpack, Boss music via record.* sounds, Custom item art (needs pack + mappings), Geyser custom_mappings/ folder, Geyser, fortuneandfavors-geyser-mappings.json, Geyser packs/ folder (+21 more)

### Community 73 - "Entity AI Mixins"
Cohesion: 0.07
Nodes (9): AbstractFurnaceBlockEntityAccessor, AbstractFurnaceBlockEntityMixin, CreeperAccessor, DragonFightAccessor, DragonFightMixin, MobGoalAccessor, MobTargetAccessor, VanishEffectParticleInvoker (+1 more)

### Community 75 - "Boss Arrivals & Revenants"
Cohesion: 0.10
Nodes (4): Arrival, Fight, ScarletDevilManager, Spear

### Community 76 - "Soulbound & Sell Values"
Cohesion: 0.05
Nodes (5): LookSnapshot, TokenManager, AuctionPriceMenu, ItemEntityMixin, ServerPlayerDropMixin

### Community 77 - "Lottery"
Cohesion: 0.08
Nodes (3): LotteryManager, RealActivityLog, ServerNewspaperManager

### Community 78 - "Ender Weapons Texture Script"
Cohesion: 0.09
Nodes (24): line(), main(), star(), starfall(), voidfang(), frostbound(), main(), blade() (+16 more)

### Community 79 - "Spawner Manager"
Cohesion: 0.11
Nodes (3): SpawnerManager, SpawnerRec, SpawnerMenu

### Community 81 - "Map Plots & Presets"
Cohesion: 0.11
Nodes (6): BlockData, CustomMap, EditSession, MapEditor, MarkerDef, SavedEditor

### Community 82 - "Client Interaction Mixins"
Cohesion: 0.09
Nodes (7): ItemInHandRendererMixin, MinecraftMixin, MultiPlayerGameModeMixin, FoodDataMixin, ItemMixin, ServerPlayerGameModeMixin, SwordBlockStartMixin

### Community 83 - "Projectile & NBT Hooks"
Cohesion: 0.10
Nodes (5): ExplosionRebuildManager, Pending, CapturedBlock, BridgeEggMixin, ServerExplosionMixin

### Community 84 - "Anticheat Enforcement"
Cohesion: 0.08
Nodes (3): Violation, AntiCheatPolicy, Dial

### Community 85 - "Restore & Commands Bridge"
Cohesion: 0.10
Nodes (5): ModCommandsBridge, RestoreMenu, Archived, LastInventoryHolder, SnapshotEntry

### Community 87 - "Boss Empowerment"
Cohesion: 0.10
Nodes (4): BossEmpowerment, Watched, RareMobVariantManager, VariantState

### Community 88 - "Wither Rework"
Cohesion: 0.10
Nodes (3): PendingRemoval, PendingTransform, WitherReworkManager

### Community 89 - "Time Lord Texture Script"
Cohesion: 0.12
Nodes (30): blank(), case(), main(), pocket_watch(), pocket_watch_ii(), space_time_rift(), arc(), blob() (+22 more)

### Community 91 - "Leaderboard Categories"
Cohesion: 0.10
Nodes (8): Category, BOSSES, BOUNTIES, JOBS, MONEY, LeaderboardManager, Stats, LeaderboardMenu

### Community 93 - "Arena Guardian Moves"
Cohesion: 0.11
Nodes (8): Type, CRYSTAL_CAVERN, DEEP_MINE, FROZEN_CRYPT, MAGMA_FORGE, MONSTER_CAVE, SUNKEN_TEMPLE, VOID

### Community 94 - "Jobs"
Cohesion: 0.12
Nodes (3): Job, JobManager, Template

### Community 96 - "Warden Entity Hooks"
Cohesion: 0.08
Nodes (5): Cut, ExcaliburSlash, FishingRodMixin, LevelDestroyBlockMixin, WardenMixin

### Community 99 - "Sea & Sky Texture Script"
Cohesion: 0.11
Nodes (18): abyssal_chain(), abyssal_pearl(), canvas(), chest(), drowned_loot_box(), gale_chakram(), gale_core(), gale_loot_box() (+10 more)

### Community 101 - "Machine Tuning"
Cohesion: 0.12
Nodes (3): MachineTuning, Record, MachineUpgradeMenu

### Community 103 - "Docs: Features & Changelog"
Cohesion: 0.09
Nodes (31): Alchemy Hall, Anticheat, Auto-Replant, Changelog (CHANGELOG.md), /changelog command, Checker Hopper, Clockwork King, CombatGear per-player leak fix (+23 more)

### Community 104 - "Anticheat Container Clicks"
Cohesion: 0.16
Nodes (3): Client, PacketEngine, Verdict

### Community 106 - "Market View Menu"
Cohesion: 0.12
Nodes (3): MarketView, StockMenus, StockTradeMenu

### Community 107 - "Boss Bar Texture Script"
Cohesion: 0.08
Nodes (9): bar(), cracked_horn(), cracks(), frame_png(), main(), mix(), patterned(), shade() (+1 more)

### Community 108 - "Chest Shops"
Cohesion: 0.16
Nodes (4): ChestShop, ChestShopManager, ItemTotal, LedgerEntry

### Community 112 - "Anticheat Aim Analysis"
Cohesion: 0.12
Nodes (5): Aim, Clicks, CombatStats, Reading, Rotation

### Community 116 - "Structure Footprints"
Cohesion: 0.12
Nodes (3): Footprint, RoomAt, Spot

### Community 117 - "Anvil Prompt Menu"
Cohesion: 0.11
Nodes (3): AnvilPromptMenu, AuctionSearchMenu, TokenRenameMenu

### Community 119 - "Feature Config Menu"
Cohesion: 0.10
Nodes (4): AuctionMinutesMenu, FeatureConfigMenu, PricePromptMenu, OpCommandMenu

### Community 120 - "Enderheart Texture Script"
Cohesion: 0.11
Nodes (11): main(), relight(), draw_icon(), main(), vanilla_item_definitions(), verify_vanilla_fallbacks(), write_json(), canvas() (+3 more)

### Community 121 - "Daily & Weekly Challenges"
Cohesion: 0.13
Nodes (3): Challenge, DailyWeeklyChallengeManager, ChallengesMenu

### Community 122 - "Client Movement Packets"
Cohesion: 0.12
Nodes (4): ConnectionAccessor, HandshakeAddressMixin, ServerOutboundMixin, ServerAddress

### Community 123 - "Backpack Music Client"
Cohesion: 0.13
Nodes (6): BackpackMusic, BoomboxSong, Desired, FollowingSong, PersonalSong, FortuneFavorsModClient

### Community 127 - "Server Events"
Cohesion: 0.14
Nodes (8): Event, FISHING_FRENZY, GUARD_SHIFT, LOCKDOWN, ORE_RUSH, PIT_NIGHT, WARDEN_PURGE, PrisonEvents

### Community 129 - "Puppeteer Gear"
Cohesion: 0.10
Nodes (3): Ally, PuppeteerGear, Tie

### Community 130 - "Scarlet Gear & Rituals"
Cohesion: 0.14
Nodes (4): Ritual, SafeDeath, ScarletGear, Servant

### Community 132 - "Ender Dragon Texture Script"
Cohesion: 0.16
Nodes (13): bow_frame(), dragon_scale(), enderheart(), enderheart_awakened(), grid(), heart_of_the_end(), main(), paint() (+5 more)

### Community 134 - "Lag-Compensated Hit Checks"
Cohesion: 0.11
Nodes (5): BlockHitResultCompat, LagCompensatedHistory, Sample, Snapshot, WorldSnapshotHistory

### Community 135 - "Container & Door Interaction"
Cohesion: 0.11
Nodes (3): FireBlockMixin, FlowingFluidMixin, PistonBaseBlockMixin

### Community 137 - "Fix Textures Script"
Cohesion: 0.16
Nodes (12): canvas(), clockwork_gauntlet(), clockwork_trophy(), evoker_cloak(), evoker_spellbook(), excalibur(), illusioner_cloak(), illusioner_spellbook() (+4 more)

### Community 138 - "Mixin Conditional Wraps"
Cohesion: 0.16
Nodes (3): RaidAccessorMixin, RaidMixin, RaidsAccessorMixin

### Community 140 - "VFX Texture Kinds"
Cohesion: 0.09
Nodes (23): Tex, ARC, BLOB, BOLT, BUBBLE, CRACK, CRYSTAL, EMBER (+15 more)

### Community 141 - "Bet & Combat Style"
Cohesion: 0.11
Nodes (5): Bet, CombatStyle, LEGACY, MODERN, SavedPlayer

### Community 142 - "Boss Codex"
Cohesion: 0.15
Nodes (3): BossCodexManager, BossEntry, FirstEverRecordManager

### Community 143 - "Cosmetics"
Cohesion: 0.17
Nodes (3): Cosmetic, CosmeticManager, CosmeticsMenu

### Community 145 - "Performance Monitor"
Cohesion: 0.12
Nodes (3): PerfMonitor, Row, Stats

### Community 146 - "Credits & End Music"
Cohesion: 0.14
Nodes (4): CreditsMusic, EndIntroMusic, Cut, OneShotTrack

### Community 147 - "Codex Pages"
Cohesion: 0.12
Nodes (3): ChamberPage, DungeonPage, ExpeditionCodexMenu

### Community 149 - "Claim Menu Items"
Cohesion: 0.16
Nodes (5): Kind, CLAIMS, MACHINES, SPAWNERS, RecoveryMenu

### Community 154 - "Chest Shop Config Menu"
Cohesion: 0.15
Nodes (6): ChestShopConfigMenu, PromptMode, CURRENCY, DEFAULT_PRICE, ITEM_PRICE, ShopPromptMenu

### Community 156 - "Crystal Kinds"
Cohesion: 0.12
Nodes (12): CrystalKind, ANTI_GRAV, CAGED, CURSED, FIERY, FORCEFIELD, LASER, LAUNCHER (+4 more)

### Community 162 - "Villager Trades"
Cohesion: 0.15
Nodes (3): FallingBlockMixin, MonsterSpawnRulesMixin, VillagerTradeMixin

### Community 164 - "Expedition Board"
Cohesion: 0.17
Nodes (4): ExpeditionBoard, Panel, Row, Sink

### Community 166 - "Puppeteer Texture Script"
Cohesion: 0.19
Nodes (9): canvas(), chest(), empty_mask(), marionette_strings(), puppeteer_loot_box(), puppeteers_mask(), save(), string_run() (+1 more)

### Community 171 - "Missing Item Texture Script"
Cohesion: 0.25
Nodes (9): bounty_compass(), canvas(), death_compass(), distant_memory_shard(), distant_memory_sword(), main(), mindbinder_shroud(), raid_banner() (+1 more)

### Community 178 - "Python Script Utilities"
Cohesion: 0.23
Nodes (4): decode(), encode(), main(), mean_absolute_error()

### Community 184 - "Raid Texture Script"
Cohesion: 0.27
Nodes (7): canvas(), raid_loot_box(), raiders_upgrader(), rect(), save(), warlord_cloak(), warlord_trophy()

### Community 189 - "Contract Progress"
Cohesion: 0.21
Nodes (7): Contract, Kind, ESCAPE, GUARDS, MINE, SELL, SLAY

### Community 195 - "Tags"
Cohesion: 0.23
Nodes (3): Tag, TagManager, TokenMenu

### Community 205 - "Price Tiers"
Cohesion: 0.24
Nodes (5): Tier, ELITE, PENNY, PREMIUM, STANDARD

### Community 211 - "Game Profile Properties"
Cohesion: 0.54
Nodes (3): PropertyMap, PropertyMap, PropertyMap

### Community 217 - "Death Drop Guard"
Cohesion: 0.29
Nodes (5): DeathDropGuard, Hold, Outcome, COSMETIC, RE_GRANTED

### Community 225 - "Claim Results"
Cohesion: 0.29
Nodes (7): ClaimResult, CANT_AFFORD, INVALID, LIMIT, OK, OVERLAP, WRITE_DISABLED

### Community 226 - "World Anomalies"
Cohesion: 0.29
Nodes (6): Anomaly, BLOOD_MOON, GILDED_CACHES, RICH_VEINS, STABLE, UNSTABLE

### Community 227 - "Interaction Kinds"
Cohesion: 0.29
Nodes (6): Interact, ELIXIR, RELIQUARY, RUNE, SUPPLY, WAGER

### Community 232 - "Bot Hand Actions"
Cohesion: 0.33
Nodes (6): BotHands, BOW, CONSUME, NONE, RIPTIDE, SHIELD

### Community 234 - "Alert Levels"
Cohesion: 0.33
Nodes (6): Alert, ALERT, CLEAR, CRITICAL, LOCKDOWN, SUSPICIOUS

### Community 237 - "Duel Phases"
Cohesion: 0.40
Nodes (5): Phase, COUNTDOWN, ENDED, FIGHT, LOBBY

## Knowledge Gaps
- **322 isolated node(s):** `build-jar.sh script`, `GLOW`, `SPARK`, `MOTE`, `RING` (+317 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 1479 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **114 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `EnderDragonManager` connect `Ender Dragon Manager` to `Warden Entity Hooks`, `Client HUD Mixins`, `Fusion Particle Effects`, `Ender Dragon Ascension`, `Entity AI Mixins`, `Combat Event Hooks`, `Boss Move Sequencing`, `Crystal Kinds`, `Bedrock Music`?**
  _High betweenness centrality (0.050) - this node is a cross-community bridge._
- **What connects `build-jar.sh script`, `GLOW`, `SPARK` to the rest of the system?**
  _322 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Economy Core & Reflection` be split into smaller, more focused modules?**
  _Cohesion score 0.012294137970747437 - nodes in this community are weakly interconnected._
- **Why does `DuelManager` connect `Duels & Bedwars Shop` to `Command Registration`, `Player Admin & Moderation`, `Container & Door Interaction`, `Vanilla Type References A`, `Mixin Conditional Wraps`, `Auctions & Persistence Types`, `Save Data & Serialization`, `Menu Classes Index`, `Bet & Combat Style`, `Combat Event Hooks`, `Wagers & Spectator Bets`, `Duel Bot AI`, `Bot Profiles`, `Duel Sessions & Spectating`, `Network Packet Mixins`, `Client Packet Types`, `Draft Mode`, `Elevator Blocks`, `Duel Challenges & Leaderboard`, `Item Mixins (MixinExtras)`, `Hopper Machines`, `Bot Difficulty Menu`, `Spectator Mode`, `Lethal Damage Mixins`, `Client HUD Mixins`, `Soulbound & Sell Values`, `Map Plots & Presets`, `Client Interaction Mixins`, `Projectile & NBT Hooks`, `Duel Plot Cleanup`, `Bedwars Upgrade Menu`, `Warden Entity Hooks`, `Duel Arenas`, `Golden Apple Heads`, `Bot Hand Actions`, `Duel Phases`?**
  _High betweenness centrality (0.026) - this node is a cross-community bridge._
- **Should `Command Registration` be split into smaller, more focused modules?**
  _Cohesion score 0.026776776776776777 - nodes in this community are weakly interconnected._
- **Why does `BossManager` connect `Economy Core & Reflection` to `Item Stacks & Special Loot`, `Boss Spawning & Bodies`, `Boss Drops & Rewards`, `Vanilla Type References A`, `Stone Golem Tuning`, `Auctions & Persistence Types`, `Save Data & Serialization`, `Combat Event Hooks`, `Network Packet Mixins`, `Client Packet Types`, `Mob Spawning & Chunks`, `Contracts & Downed State`, `Elevator Blocks`, `Item Mixins (MixinExtras)`, `Hopper Machines`, `Lethal Damage Mixins`, `Command Selector Mixins`, `Fusion Particle Effects`, `Blood Thralls`, `Text Displays & Cooldowns`, `Boss Arrivals & Revenants`, `Soulbound & Sell Values`, `Boss Empowerment`, `Captain Rewards`, `Warden Entity Hooks`, `Golden Apple Heads`, `Possessed Player`?**
  _High betweenness centrality (0.026) - this node is a cross-community bridge._
- **Should `Item Stacks & Special Loot` be split into smaller, more focused modules?**
  _Cohesion score 0.025819684493107713 - nodes in this community are weakly interconnected._