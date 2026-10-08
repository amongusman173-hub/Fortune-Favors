# Graph Report - Fortune-Favors  (2026-10-08)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 11741 nodes · 50702 edges · 264 communities (121 shown, 143 thin omitted)
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 312 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `554bcad1`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Community 0
- Community 1
- Community 2
- Community 3
- Community 4
- Community 5
- Community 6
- Community 7
- Community 8
- Community 9
- Community 10
- Community 11
- Community 12
- Community 13
- Community 14
- Community 15
- Community 16
- Community 17
- Community 18
- Community 19
- Community 20
- Community 21
- Community 22
- Community 23
- Community 24
- Community 25
- Community 26
- Community 27
- Community 28
- Community 29
- Community 30
- Community 31
- Community 32
- Community 33
- Community 34
- Community 35
- Community 36
- Community 37
- Community 38
- Community 39
- Community 40
- Community 41
- Community 42
- Community 43
- Community 44
- Community 45
- Community 46
- Community 47
- Community 48
- Community 49
- Community 51
- Community 52
- Community 53
- Community 54
- Community 55
- Community 56
- Community 57
- Community 59
- Community 60
- Community 61
- Community 62
- Community 63
- Community 64
- Community 65
- Community 66
- Community 67
- Community 68
- Community 69
- Community 70
- Community 71
- Community 73
- Community 74
- Community 75
- Community 76
- Community 77
- Community 78
- Community 79
- Community 80
- Community 81
- Community 82
- Community 83
- Community 84
- Community 85
- Community 86
- Community 87
- Community 88
- Community 90
- Community 91
- Community 92
- Community 93
- Community 94
- Community 95
- Community 96
- Community 97
- Community 98
- Community 99
- Community 100
- Community 101
- Community 102
- Community 104
- Community 105
- Community 106
- Community 107
- Community 108
- Community 109
- Community 110
- Community 111
- Community 112
- Community 113
- Community 115
- Community 116
- Community 117
- Community 118
- Community 119
- Community 120
- Community 121
- Community 122
- Community 123
- Community 124
- Community 125
- Community 126
- Community 127
- Community 128
- Community 129
- Community 130
- Community 131
- Community 132
- Community 133
- Community 134
- Community 135
- Community 136
- Community 137
- Community 138
- Community 139
- Community 140
- Community 141
- Community 142
- Community 143
- Community 144
- Community 145
- Community 146
- Community 147
- Community 148
- Community 149
- Community 150
- Community 151
- Community 152
- Community 154
- Community 155
- Community 156
- Community 157
- Community 158
- Community 159
- Community 160
- Community 162
- Community 163
- Community 164
- Community 165
- Community 166
- Community 167
- Community 168
- Community 169
- Community 170
- Community 171
- Community 172
- Community 173
- Community 174
- Community 175
- Community 176
- Community 177
- Community 178
- Community 179
- Community 180
- Community 181
- Community 182
- Community 183
- Community 184
- Community 185
- Community 187
- Community 188
- Community 189
- Community 190
- Community 191
- Community 192
- Community 193
- Community 194
- Community 195
- Community 196
- Community 197
- Community 198
- Community 199
- Community 200
- Community 201
- Community 202
- Community 203
- Community 204
- Community 205
- Community 206
- Community 207
- Community 208
- Community 209
- Community 210
- Community 211
- Community 212
- Community 214
- Community 215
- Community 216
- Community 219
- Community 220
- Community 221
- Community 222
- Community 223
- Community 224
- Community 225
- Community 227
- Community 228
- Community 229
- Community 230
- Community 231
- Community 232
- Community 233
- Community 234
- Community 235
- Community 236
- Community 237
- Community 239
- Community 240
- Community 241
- Community 242
- Community 243
- Community 244
- Community 245
- Community 246
- Community 247
- Community 248
- Community 249
- Community 250
- Community 251
- Community 253
- Community 255
- Community 256
- Community 258
- Community 260

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
- `ShopPromptMenu` --references--> `ChestShop`  [EXTRACTED]
  src/main/java/com/fortuneandfavors/menu/ChestShopConfigMenu.java → src/main/java/com/fortuneandfavors/economy/ChestShopManager.java
- `ShopPromptMenu` --inherits--> `AnvilPromptMenu`  [EXTRACTED]
  src/main/java/com/fortuneandfavors/menu/ChestShopConfigMenu.java → src/main/java/com/fortuneandfavors/menu/AnvilPromptMenu.java
- `ScoreboardManager` --references--> `Panel`  [EXTRACTED]
  src/main/java/com/fortuneandfavors/economy/ScoreboardManager.java → src/main/java/com/fortuneandfavors/economy/ExpeditionBoard.java
- `PrisonCellblock` --references--> `Escape`  [EXTRACTED]
  src/main/java/com/fortuneandfavors/economy/PrisonCellblock.java → src/main/java/com/fortuneandfavors/economy/PlayerFeatTracker.java
- `TimeLordManager` --references--> `Fight`  [EXTRACTED]
  src/main/java/com/fortuneandfavors/economy/TimeLordManager.java → src/main/java/com/fortuneandfavors/economy/ClockworkKingManager.java

## Import Cycles
- None detected.

## Communities (264 total, 143 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.01
Nodes (19): Arrival, BodyTakeover, BossManager, CorruptionHold, FistWave, GolemCrumble, HomingCharge, MindMine (+11 more)

### Community 1 - "Community 1"
Cohesion: 0.01
Nodes (76): BossHealthOverlayMixin, EquipmentLayerRendererMixin, HashedPatchMapMixin, LootBoxScreenMixin, MinecraftMixin, MultiPlayerGameModeMixin, CoverKey, CapturedBlock (+68 more)

### Community 2 - "Community 2"
Cohesion: 0.02
Nodes (5): SculkOrb, SculkOrbProjectile, SafeDeath, ItemEntityMixin, ModItems

### Community 3 - "Community 3"
Cohesion: 0.03
Nodes (3): ModCommands, RaidBossRow, RecoveryResult

### Community 4 - "Community 4"
Cohesion: 0.03
Nodes (16): Fusion, BossVfx, DrownedSovereignManager, Fight, Pending, Mark, EndRift, Rupture (+8 more)

### Community 5 - "Community 5"
Cohesion: 0.04
Nodes (5): CustomEnchantments, MobGoalAccessor, WitherBossAccessor, NkiSnapshotHolder, Safe

### Community 6 - "Community 6"
Cohesion: 0.04
Nodes (3): FortuneFavorsMod, JsonUtil, ModPlatform

### Community 7 - "Community 7"
Cohesion: 0.03
Nodes (13): ActiveBoss, FistBlock, GolemRock, GooZone, KingMove, PoisonZone, SlimeHazard, SnowHit (+5 more)

### Community 8 - "Community 8"
Cohesion: 0.02
Nodes (17): Ability, ARROWS, NONE, SHIELD, TOTEM, Fight, MobTether, Possession (+9 more)

### Community 10 - "Community 10"
Cohesion: 0.10
Nodes (10): Machine, EconomyManager, TokenManager, ShopPromptMenu, ModSounds, Chat, GuiUtil, InventoryHelper (+2 more)

### Community 11 - "Community 11"
Cohesion: 0.03
Nodes (13): BossGrounding, ServerGamePacketListenerImpl, ChronoFx, Escape, Aging, Bomb, Deferred, Fight (+5 more)

### Community 12 - "Community 12"
Cohesion: 0.03
Nodes (12): Guild, GuildManager, LeaveRequest, Mail, Rank, FOUNDER, MEMBER, OFFICER (+4 more)

### Community 13 - "Community 13"
Cohesion: 0.02
Nodes (4): Advancements, GolemBlock, SwordBlockManager, ServerOutboundMixin

### Community 14 - "Community 14"
Cohesion: 0.03
Nodes (4): ChamberPage, ExpeditionManager, SquadRow, TypeStats

### Community 15 - "Community 15"
Cohesion: 0.03
Nodes (8): Cuffs, Escape, Lock, IDLE, LIT, OPENED, LooseBrick, PrisonCellblock

### Community 16 - "Community 16"
Cohesion: 0.03
Nodes (6): Bet, DuelManager, SpectatorSession, ToolTier, WagerRequest, WagerStaged

### Community 17 - "Community 17"
Cohesion: 0.03
Nodes (3): AntiCheat, BreakClaim, HackLine

### Community 18 - "Community 18"
Cohesion: 0.04
Nodes (3): Momentum, PrisonManager, Seam

### Community 19 - "Community 19"
Cohesion: 0.04
Nodes (23): Auction, AuctionManager, Category, ALL, BLOCKS, FOOD, GEAR, MACHINES (+15 more)

### Community 20 - "Community 20"
Cohesion: 0.07
Nodes (4): Arena, Body, Room, State

### Community 22 - "Community 22"
Cohesion: 0.04
Nodes (5): RaidGearManager, Guard, SovereignGear, VanishManager, ClientResync

### Community 23 - "Community 23"
Cohesion: 0.05
Nodes (4): CCEnchantments, ChainfireShoot, QueuedChainShot, AnvilMixin

### Community 24 - "Community 24"
Cohesion: 0.06
Nodes (4): Entry, ItemSorter, ItemSorterMenu, HopperBlockEntityMixin

### Community 25 - "Community 25"
Cohesion: 0.04
Nodes (9): DuelBot, BotDifficulty, EASY, HACKER, HARD, NORMAL, TRAIN, BotRead (+1 more)

### Community 26 - "Community 26"
Cohesion: 0.05
Nodes (6): Bounty, BountyManager, SculkFoodManager, AuctionPriceMenu, MineDepositPromptMenu, RedeemerPayoutMenu

### Community 27 - "Community 27"
Cohesion: 0.06
Nodes (5): BotBlow, Duel, FfaBoard, Participant, SavedPlayer

### Community 28 - "Community 28"
Cohesion: 0.08
Nodes (26): FfParticle, Tex, ARC, BLOB, BOLT, BUBBLE, CRACK, CRYSTAL (+18 more)

### Community 29 - "Community 29"
Cohesion: 0.06
Nodes (6): Contract, Downed, DynamicContractsManager, Target, WitherWatch, DeliverMenu

### Community 30 - "Community 30"
Cohesion: 0.04
Nodes (19): Anomaly, BLOOD_MOON, GILDED_CACHES, RICH_VEINS, STABLE, UNSTABLE, ArenaReport, ExpeditionProgression (+11 more)

### Community 31 - "Community 31"
Cohesion: 0.04
Nodes (6): AntiCheatStore, Exemption, Punishment, Record, Review, Spectate

### Community 32 - "Community 32"
Cohesion: 0.05
Nodes (12): Claim, ClaimManager, ClaimResult, CANT_AFFORD, INVALID, LIMIT, OK, OVERLAP (+4 more)

### Community 33 - "Community 33"
Cohesion: 0.04
Nodes (3): MirageCastleManager, Place, Stand

### Community 34 - "Community 34"
Cohesion: 0.06
Nodes (8): VoidShaperGear, Fight, Held, Kind, Lift, Loose, Shot, VoidShaperManager

### Community 35 - "Community 35"
Cohesion: 0.06
Nodes (5): Track, Violation, MotionModel, Tick, MovementPhysics

### Community 36 - "Community 36"
Cohesion: 0.04
Nodes (29): Challenge, CombatStyle, LEGACY, MODERN, DuelMode, ARCHERY, AXEDUEL, BEDWARS (+21 more)

### Community 37 - "Community 37"
Cohesion: 0.05
Nodes (8): SpawnAnim, TempBlock, MiningPity, MiningZoneManager, Zone, NaturalBlocks, LevelDestroyBlockMixin, LastSpot

### Community 38 - "Community 38"
Cohesion: 0.04
Nodes (10): BountyMenu, CodexMenu, CuffMenu, MenuHubMenu, NewsMenu, OpCommandMenu, RecipeMenu, RecordsMenu (+2 more)

### Community 39 - "Community 39"
Cohesion: 0.07
Nodes (8): ClockworkKingManager, Fight, Plate, Role, BLADE, DRONE, PISTON, TURRET

### Community 41 - "Community 41"
Cohesion: 0.06
Nodes (36): line(), main(), star(), starfall(), voidfang(), frostbound(), main(), blade() (+28 more)

### Community 42 - "Community 42"
Cohesion: 0.05
Nodes (4): FireBlockMixin, ServerPlayerGameModeMixin, ModEvents, Claim

### Community 43 - "Community 43"
Cohesion: 0.03
Nodes (58): Move, ABYSSAL_ROAR, ASCENDANT_BLINK, AURA_OF_THE_END, BREATH_LANCE, BREATH_NOVA, CRYSTAL_BLOOM, CRYSTAL_RESONANCE (+50 more)

### Community 44 - "Community 44"
Cohesion: 0.06
Nodes (35): astral_compass(), astral_mantle(), automaton_armor(), canvas(), chest(), clockwork_core(), clockwork_gauntlet(), clockwork_loot_box() (+27 more)

### Community 46 - "Community 46"
Cohesion: 0.03
Nodes (51): Chamber, ALCHEMY, ARENA, ARMOURY, BATHHOUSE, BROKEN_CROSSING, CACHE, CALM_CAMP (+43 more)

### Community 47 - "Community 47"
Cohesion: 0.09
Nodes (5): MarketManager, Position, Receipt, Stock, Wealth

### Community 49 - "Community 49"
Cohesion: 0.07
Nodes (3): KindApi, SelfTest, Toggle

### Community 51 - "Community 51"
Cohesion: 0.06
Nodes (6): Entry, Flow, Trade, TradeManager, CashInputMenu, TradeMenu

### Community 53 - "Community 53"
Cohesion: 0.08
Nodes (3): LootBackpack, LootBackpackMenu, LootChestMenu

### Community 54 - "Community 54"
Cohesion: 0.09
Nodes (3): Chakram, SeaAndSkyGear, SkySlam

### Community 55 - "Community 55"
Cohesion: 0.06
Nodes (16): ClothBridge, BossDespawn, FIGHTERS, NEVER, SUMMONER, ClientCategory, FfClientConfig, ServerCategory (+8 more)

### Community 56 - "Community 56"
Cohesion: 0.08
Nodes (5): BossChat, Bolt, Fight, Mark, StarboundMagisterManager

### Community 57 - "Community 57"
Cohesion: 0.08
Nodes (5): DeathCompassManager, LastDeath, Waypoint, WormholeManager, WormholeMenu

### Community 59 - "Community 59"
Cohesion: 0.10
Nodes (3): Emerald, EmeraldSovereignManager, Fight

### Community 60 - "Community 60"
Cohesion: 0.11
Nodes (3): Party, PartyManager, PartyMenu

### Community 61 - "Community 61"
Cohesion: 0.07
Nodes (5): Astral, Dive, EnderGear, Marks, Star

### Community 62 - "Community 62"
Cohesion: 0.10
Nodes (6): Account, BankManager, CatchUp, Growth, Movement, BankMenu

### Community 63 - "Community 63"
Cohesion: 0.09
Nodes (5): Bat, Ritual, ScarletGear, Servant, Spear

### Community 64 - "Community 64"
Cohesion: 0.09
Nodes (9): Category, BUILDING, EXCLUSIVE, FOOD, REDSTONE, TOOLS, ShopData, ShopEntry (+1 more)

### Community 65 - "Community 65"
Cohesion: 0.09
Nodes (3): SpawnerManager, SpawnerRec, SpawnerMenu

### Community 67 - "Community 67"
Cohesion: 0.10
Nodes (6): Band, COMMON, EPIC, LEGENDARY, RARE, LootBoxMenu

### Community 69 - "Community 69"
Cohesion: 0.10
Nodes (6): BlockData, CustomMap, EditSession, MapEditor, MarkerDef, SavedEditor

### Community 70 - "Community 70"
Cohesion: 0.09
Nodes (3): ChunkAnchor, FarmMachines, ChunkAnchorMenu

### Community 71 - "Community 71"
Cohesion: 0.09
Nodes (5): Cosmetic, CosmeticManager, MysteryChestManager, CosmeticsMenu, MysteryChestMenu

### Community 74 - "Community 74"
Cohesion: 0.09
Nodes (5): DisplayPrefsManager, Tag, TagManager, TitleManager, TagsMenu

### Community 76 - "Community 76"
Cohesion: 0.10
Nodes (4): MachineTuning, Record, MachineUpgradeMenu, AbstractFurnaceBlockEntityMixin

### Community 77 - "Community 77"
Cohesion: 0.09
Nodes (5): ModCommandsBridge, RestoreMenu, Archived, LastInventoryHolder, SnapshotEntry

### Community 78 - "Community 78"
Cohesion: 0.09
Nodes (3): StockMarketMenu, MarketView, StockMenus

### Community 79 - "Community 79"
Cohesion: 0.10
Nodes (9): DungeonPage, Type, CRYSTAL_CAVERN, DEEP_MINE, FROZEN_CRYPT, MAGMA_FORGE, MONSTER_CAVE, SUNKEN_TEMPLE (+1 more)

### Community 82 - "Community 82"
Cohesion: 0.09
Nodes (8): Pit, PitMode, BOUNTY, CHAMPION, DUEL, GAUNTLET, WARDEN, PitMenu

### Community 83 - "Community 83"
Cohesion: 0.12
Nodes (29): disc(), excalibur(), main(), paint_line(), seg(), arc(), blob(), bolt() (+21 more)

### Community 86 - "Community 86"
Cohesion: 0.10
Nodes (8): Category, BOSSES, BOUNTIES, JOBS, MONEY, LeaderboardManager, Stats, LeaderboardMenu

### Community 90 - "Community 90"
Cohesion: 0.11
Nodes (18): abyssal_chain(), abyssal_pearl(), canvas(), chest(), drowned_loot_box(), gale_chakram(), gale_core(), gale_loot_box() (+10 more)

### Community 91 - "Community 91"
Cohesion: 0.13
Nodes (3): ExplosionRebuildManager, Pending, ServerExplosionMixin

### Community 92 - "Community 92"
Cohesion: 0.13
Nodes (3): Job, JobManager, Template

### Community 94 - "Community 94"
Cohesion: 0.11
Nodes (4): Execution, Phase, CELL, CHAIR

### Community 95 - "Community 95"
Cohesion: 0.15
Nodes (3): BackpackJukebox, Playing, BossMusic

### Community 98 - "Community 98"
Cohesion: 0.11
Nodes (9): Answer, CLAIM, DEFER, INNOCENT, Blow, Handler, LethalBlows, Rule (+1 more)

### Community 99 - "Community 99"
Cohesion: 0.12
Nodes (3): BotProfile, BotDifficultyMenu, CustomBotMenu

### Community 100 - "Community 100"
Cohesion: 0.11
Nodes (3): Challenge, DailyWeeklyChallengeManager, ChallengesMenu

### Community 102 - "Community 102"
Cohesion: 0.08
Nodes (9): bar(), cracked_horn(), cracks(), frame_png(), main(), mix(), patterned(), shade() (+1 more)

### Community 105 - "Community 105"
Cohesion: 0.16
Nodes (3): Client, PacketEngine, Verdict

### Community 108 - "Community 108"
Cohesion: 0.11
Nodes (3): Panel, Row, ScoreboardManager

### Community 111 - "Community 111"
Cohesion: 0.12
Nodes (5): Aim, Clicks, CombatStats, Reading, Rotation

### Community 112 - "Community 112"
Cohesion: 0.14
Nodes (5): DailyLoginStreakManager, PlayerStreak, RewardPlan, Shift, DailyRewardsMenu

### Community 115 - "Community 115"
Cohesion: 0.10
Nodes (3): AuctionMinutesMenu, FeatureConfigMenu, PricePromptMenu

### Community 119 - "Community 119"
Cohesion: 0.11
Nodes (3): Ally, PuppeteerGear, Tie

### Community 120 - "Community 120"
Cohesion: 0.11
Nodes (11): main(), relight(), draw_icon(), main(), vanilla_item_definitions(), verify_vanilla_fallbacks(), write_json(), canvas() (+3 more)

### Community 122 - "Community 122"
Cohesion: 0.13
Nodes (8): Event, FISHING_FRENZY, GUARD_SHIFT, LOCKDOWN, ORE_RUSH, PIT_NIGHT, WARDEN_PURGE, PrisonEvents

### Community 124 - "Community 124"
Cohesion: 0.11
Nodes (10): bow_model(), main(), model_entry(), spear_model(), vanilla(), write_dispatch(), decode(), encode() (+2 more)

### Community 125 - "Community 125"
Cohesion: 0.16
Nodes (13): bow_frame(), dragon_scale(), enderheart(), enderheart_awakened(), grid(), heart_of_the_end(), main(), paint() (+5 more)

### Community 128 - "Community 128"
Cohesion: 0.15
Nodes (11): bed_nodes(), conflicted(), custom_items(), identifiers(), java_sounds(), leaf_models(), main(), mod_version() (+3 more)

### Community 132 - "Community 132"
Cohesion: 0.14
Nodes (3): BossCodexManager, BossEntry, FirstEverRecordManager

### Community 133 - "Community 133"
Cohesion: 0.14
Nodes (3): Footprint, RoomAt, Spot

### Community 134 - "Community 134"
Cohesion: 0.16
Nodes (12): canvas(), clockwork_gauntlet(), clockwork_trophy(), evoker_cloak(), evoker_spellbook(), excalibur(), illusioner_cloak(), illusioner_spellbook() (+4 more)

### Community 135 - "Community 135"
Cohesion: 0.13
Nodes (5): BlockHitResultCompat, LagCompensatedHistory, Sample, Snapshot, WorldSnapshotHistory

### Community 138 - "Community 138"
Cohesion: 0.12
Nodes (3): PerfMonitor, Row, Stats

### Community 143 - "Community 143"
Cohesion: 0.16
Nodes (5): Kind, CLAIMS, MACHINES, SPAWNERS, RecoveryMenu

### Community 144 - "Community 144"
Cohesion: 0.17
Nodes (5): BackpackMusic, BoomboxSong, Desired, FollowingSong, PersonalSong

### Community 146 - "Community 146"
Cohesion: 0.13
Nodes (7): Contract, Kind, ESCAPE, GUARDS, MINE, SELL, SLAY

### Community 149 - "Community 149"
Cohesion: 0.12
Nodes (12): CrystalKind, ANTI_GRAV, CAGED, CURSED, FIERY, FORCEFIELD, LASER, LAUNCHER (+4 more)

### Community 154 - "Community 154"
Cohesion: 0.17
Nodes (3): GraveData, GraveRecord, NiceKeepInventoryManager

### Community 156 - "Community 156"
Cohesion: 0.19
Nodes (5): ChestShopConfigMenu, PromptMode, CURRENCY, DEFAULT_PRICE, ITEM_PRICE

### Community 164 - "Community 164"
Cohesion: 0.17
Nodes (3): EndIntroMusic, Cut, OneShotTrack

### Community 167 - "Community 167"
Cohesion: 0.18
Nodes (3): ChestShopManager, ItemTotal, LedgerEntry

### Community 168 - "Community 168"
Cohesion: 0.17
Nodes (4): ExpeditionBoard, Panel, Row, Sink

### Community 169 - "Community 169"
Cohesion: 0.19
Nodes (9): canvas(), chest(), empty_mask(), marionette_strings(), puppeteer_loot_box(), puppeteers_mask(), save(), string_run() (+1 more)

### Community 174 - "Community 174"
Cohesion: 0.25
Nodes (9): bounty_compass(), canvas(), death_compass(), distant_memory_shard(), distant_memory_sword(), main(), mindbinder_shroud(), raid_banner() (+1 more)

### Community 177 - "Community 177"
Cohesion: 0.20
Nodes (3): HudInvoker, CreeperAccessor, VanishEffectParticleInvoker

### Community 187 - "Community 187"
Cohesion: 0.27
Nodes (7): canvas(), raid_loot_box(), raiders_upgrader(), rect(), save(), warlord_cloak(), warlord_trophy()

### Community 189 - "Community 189"
Cohesion: 0.23
Nodes (4): AfkManager, State, SonicBoom, State

### Community 207 - "Community 207"
Cohesion: 0.33
Nodes (6): blank(), case(), main(), pocket_watch(), pocket_watch_ii(), space_time_rift()

### Community 211 - "Community 211"
Cohesion: 0.24
Nodes (5): Tier, ELITE, PENNY, PREMIUM, STANDARD

### Community 221 - "Community 221"
Cohesion: 0.54
Nodes (3): PropertyMap, PropertyMap, PropertyMap

### Community 225 - "Community 225"
Cohesion: 0.29
Nodes (5): DeathDropGuard, Hold, Outcome, COSMETIC, RE_GRANTED

### Community 227 - "Community 227"
Cohesion: 0.25
Nodes (7): Sector, CONTAINMENT, DEEP_CELL, FURNACE, MAXIMUM_SECURITY, QUARRY, YARD

### Community 232 - "Community 232"
Cohesion: 0.36
Nodes (4): blank(), main(), slime_launcher(), slime_shield()

### Community 237 - "Community 237"
Cohesion: 0.29
Nodes (6): Interact, ELIXIR, RELIQUARY, RUNE, SUPPLY, WAGER

### Community 239 - "Community 239"
Cohesion: 0.52
Nodes (5): glow(), main(), rays(), sprite(), star()

### Community 242 - "Community 242"
Cohesion: 0.33
Nodes (6): BotHands, BOW, CONSUME, NONE, RIPTIDE, SHIELD

### Community 245 - "Community 245"
Cohesion: 0.33
Nodes (6): Alert, ALERT, CLEAR, CRITICAL, LOCKDOWN, SUSPICIOUS

### Community 251 - "Community 251"
Cohesion: 0.40
Nodes (5): Phase, COUNTDOWN, ENDED, FIGHT, LOBBY

### Community 258 - "Community 258"
Cohesion: 0.67
Nodes (3): Out, BARS, VENT

## Knowledge Gaps
- **304 isolated node(s):** `HackLine`, `RaidBossRow`, `ClientCategory`, `ClientCategory`, `ALERT` (+299 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 1461 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **143 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `BossManager` connect `Community 0` to `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 7`, `Community 8`, `Community 9`, `Community 11`, `Community 13`, `Community 22`, `Community 37`, `Community 42`, `Community 45`, `Community 50`, `Community 52`, `Community 63`, `Community 73`, `Community 87`, `Community 217`?**
  _High betweenness centrality (0.034) - this node is a cross-community bridge._
- **What connects `HackLine`, `RaidBossRow`, `ClientCategory` to the rest of the system?**
  _304 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.012679999617784488 - nodes in this community are weakly interconnected._
- **Why does `ExpeditionManager` connect `Community 14` to `Community 1`, `Community 4`, `Community 5`, `Community 10`, `Community 139`, `Community 20`, `Community 150`, `Community 26`, `Community 30`, `Community 37`, `Community 42`, `Community 46`, `Community 50`, `Community 52`, `Community 53`, `Community 60`, `Community 79`, `Community 89`, `Community 98`, `Community 237`, `Community 117`?**
  _High betweenness centrality (0.028) - this node is a cross-community bridge._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.014404516284191739 - nodes in this community are weakly interconnected._
- **Why does `MirageCastleManager` connect `Community 33` to `Community 1`, `Community 98`, `Community 196`, `Community 37`, `Community 5`, `Community 7`, `Community 133`, `Community 9`, `Community 10`, `Community 4`, `Community 6`, `Community 13`, `Community 238`, `Community 212`?**
  _High betweenness centrality (0.026) - this node is a cross-community bridge._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.02477914242620125 - nodes in this community are weakly interconnected._