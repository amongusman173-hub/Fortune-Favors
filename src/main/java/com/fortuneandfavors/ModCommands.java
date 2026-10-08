package com.fortuneandfavors;

import com.fortuneandfavors.duel.DuelCommands;
import com.fortuneandfavors.economy.AfkManager;
import com.fortuneandfavors.economy.AuctionManager;
import com.fortuneandfavors.economy.BlockValues;
import com.fortuneandfavors.economy.BossManager;
import com.fortuneandfavors.economy.BountyManager;
import com.fortuneandfavors.economy.CCEnchantments;
import com.fortuneandfavors.economy.ChestShopManager;
import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.CustomEnchantments;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.LotteryManager;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.economy.PermissionManager;
import com.fortuneandfavors.economy.VanishManager;
import com.fortuneandfavors.economy.PlayerRaidManager;
import com.fortuneandfavors.economy.PrisonManager;
import com.fortuneandfavors.economy.ShopData;
import com.fortuneandfavors.economy.ShopProgression;
import com.fortuneandfavors.economy.SkillManager;
import com.fortuneandfavors.economy.SpawnerManager;
import com.fortuneandfavors.economy.TagManager;
import com.fortuneandfavors.economy.TradeManager;
import com.fortuneandfavors.economy.WormholeManager;
import com.fortuneandfavors.economy.AuctionManager.Auction;
import com.fortuneandfavors.economy.BountyManager.Bounty;
import com.fortuneandfavors.economy.ChestShopManager.ChestShop;
import com.fortuneandfavors.economy.ShopData.Category;
import com.fortuneandfavors.economy.ShopData.ShopEntry;
import com.fortuneandfavors.economy.TagManager.Tag;
import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.guild.GuildManager.Guild;
import com.fortuneandfavors.map.MapEditor;
import com.fortuneandfavors.menu.AuctionMenu;
import com.fortuneandfavors.menu.FeatureConfigMenu;
import com.fortuneandfavors.menu.GuildMailMenu;
import com.fortuneandfavors.menu.GuildMenu;
import com.fortuneandfavors.menu.GuildSkillsMenu;
import com.fortuneandfavors.menu.ItemListMenu;
import com.fortuneandfavors.menu.JobMenu;
import com.fortuneandfavors.menu.LotteryMenu;
import com.fortuneandfavors.menu.MenuHubMenu;
import com.fortuneandfavors.menu.RaidBossHelpMenu;
import com.fortuneandfavors.menu.SellMenu;
import com.fortuneandfavors.menu.ShopMenu;
import com.fortuneandfavors.menu.SkillMenu;
import com.fortuneandfavors.menu.GemShopMenu;
import com.fortuneandfavors.menu.TokenMenu;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.ChatCoalescer;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.PerfMonitor;
import com.fortuneandfavors.util.SoundUtil;
import com.fortuneandfavors.util.WorldBackup;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Unit;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.BlockHitResult;

public final class ModCommands {
   private ModCommands() {
   }

   public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                              "shop"
                           )
                           .executes(ctx -> openShop(ctx, Category.BUILDING)))
                        .then(Commands.literal("building").executes(ctx -> openShop(ctx, Category.BUILDING))))
                     .then(Commands.literal("food").executes(ctx -> openShop(ctx, Category.FOOD))))
                  .then(Commands.literal("tools").executes(ctx -> openShop(ctx, Category.TOOLS))))
               .then(Commands.literal("exclusive").executes(ctx -> openShop(ctx, Category.EXCLUSIVE))))
            .then(Commands.literal("redstone").executes(ctx -> openShop(ctx, Category.REDSTONE)))
      );
      dispatcher.register(
         (LiteralArgumentBuilder)Commands.literal("buy")
            .then(
               ((RequiredArgumentBuilder)Commands.argument("item", ItemArgument.item(context)).executes(ctx -> quickBuy(ctx, 1)))
                  .then(
                     Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> quickBuy(ctx, IntegerArgumentType.getInteger(ctx, "amount")))
                  )
            )
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("sell").executes(ModCommands::openSellGui))
               .then(Commands.literal("hand").executes(ModCommands::sellHand)))
            .then(Commands.literal("all").executes(ModCommands::sellAll))
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("balance").executes(ModCommands::balance))
               .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> balanceOther(ctx))))
            .then(
               Commands.literal("transfer")
                  .then(
                     Commands.argument("player", EntityArgument.player())
                        .then(
                           Commands.argument("amount", LongArgumentType.longArg(1L))
                              .executes(ctx -> transferBalance(ctx, EntityArgument.getPlayer(ctx, "player"), LongArgumentType.getLong(ctx, "amount")))
                        )
                  )
            )
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("money").executes(ModCommands::balance))
               .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> balanceOther(ctx))))
            .then(
               Commands.literal("transfer")
                  .then(
                     Commands.argument("player", EntityArgument.player())
                        .then(
                           Commands.argument("amount", LongArgumentType.longArg(1L))
                              .executes(ctx -> transferBalance(ctx, EntityArgument.getPlayer(ctx, "player"), LongArgumentType.getLong(ctx, "amount")))
                        )
                  )
            )
      );
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("collect").executes(ModCommands::collect));
      dispatcher.register(
         (LiteralArgumentBuilder)Commands.literal("trade")
            .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> tradeRequest(ctx, EntityArgument.getPlayer(ctx, "player"))))
      );
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("tradeaccept").executes(ctx -> tradeAccept(ctx)));
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("wormhole")
               .then(Commands.literal("accept").executes(ctx -> wormholeAnswer(ctx, true))))
            .then(Commands.literal("deny").executes(ctx -> wormholeAnswer(ctx, false)))
      );
      dispatcher.register(
         (LiteralArgumentBuilder)Commands.literal("lottery")
            .executes(ModCommands::lotteryMenu)
            .then(Commands.literal("buy").then(Commands.argument("amount", IntegerArgumentType.integer(1)).executes(ctx -> lotteryBuy(ctx, IntegerArgumentType.getInteger(ctx, "amount")))))
      );
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("bank").executes(ModCommands::openBankGui));
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("stocks").executes(ModCommands::openStocksGui));
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("token").executes(ModCommands::openTokenGui));
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("explosionrebuild")
                     .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)))
                  .executes(ctx -> explosionRebuild(ctx, null)))
               .then(Commands.literal("on").executes(ctx -> explosionRebuild(ctx, true))))
            .then(Commands.literal("off").executes(ctx -> explosionRebuild(ctx, false)))
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("ExplosionRebuild")
                     .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)))
                  .executes(ctx -> explosionRebuild(ctx, null)))
               .then(Commands.literal("on").executes(ctx -> explosionRebuild(ctx, true))))
            .then(Commands.literal("off").executes(ctx -> explosionRebuild(ctx, false)))
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("raidcooldown")
                     .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)))
                  .executes(ctx -> raidCooldown(ctx, null)))
               .then(Commands.literal("on").executes(ctx -> raidCooldown(ctx, true))))
            .then(Commands.literal("off").executes(ctx -> raidCooldown(ctx, false)))
      );
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("jobs").executes(ModCommands::openJobsGui));
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("bounty")
                  .then(
                     Commands.argument("player", EntityArgument.player())
                        .then(
                           Commands.argument("amount", LongArgumentType.longArg(1L))
                              .executes(ctx -> bountyPlace(ctx, EntityArgument.getPlayer(ctx, "player"), LongArgumentType.getLong(ctx, "amount")))
                        )
                  ))
               .then(
                  Commands.literal("cancel")
                     .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> bountyCancel(ctx, EntityArgument.getPlayer(ctx, "player"))))
               ))
            .then(Commands.literal("list").executes(ModCommands::bountyList))
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("tag")
                     .executes(ModCommands::tagShow))
                  .then(
                     Commands.literal("set")
                        .then(
                           Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> tagSet(ctx, StringArgumentType.getString(ctx, "text")))
                        )
                  ))
               .then(
                  Commands.literal("color")
                     .then(
                        Commands.argument("r", IntegerArgumentType.integer(0, 255))
                           .then(
                              Commands.argument("g", IntegerArgumentType.integer(0, 255))
                                 .then(
                                    Commands.argument("b", IntegerArgumentType.integer(0, 255))
                                       .executes(
                                          ctx -> tagColor(
                                             ctx,
                                             IntegerArgumentType.getInteger(ctx, "r"),
                                             IntegerArgumentType.getInteger(ctx, "g"),
                                             IntegerArgumentType.getInteger(ctx, "b")
                                          )
                                       )
                                 )
                           )
                     )
               ))
            .then(Commands.literal("clear").executes(ModCommands::tagClear))
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("perms")
                     .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)))
                  .then(
                     Commands.literal("give")
                        .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> permsGive(ctx, EntityArgument.getPlayer(ctx, "player"))))
                  ))
               .then(
                  Commands.literal("remove")
                     .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> permsRemove(ctx, EntityArgument.getPlayer(ctx, "player"))))
               ))
            .then(Commands.literal("list").executes(ModCommands::permsList))
      );
      dispatcher.register(
         Commands.literal("claim")
            .executes(ModCommands::openClaimGui)
            .then(Commands.literal("menu").executes(ModCommands::openClaimGui))
            .then(Commands.literal("give").executes(ModCommands::claimGive))
            .then(
               Commands.literal("abandon")
                  .executes(ModCommands::claimAbandon)
                  .then(Commands.literal("confirm").executes(ModCommands::claimAbandonConfirm))
            )
            .then(
               Commands.literal("slots")
                  .executes(ModCommands::claimSlotsInfo)
                  .then(Commands.literal("buy").executes(ModCommands::claimSlotsBuy))
            )
            .then(Commands.literal("loot").executes(ModCommands::collect))
            .then(Commands.literal("status").executes(ModCommands::claimStatus))
            .then(Commands.literal("info").executes(ModCommands::claimStatus))
            .then(
               Commands.literal("recover")
                  .requires(
                     source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
                        || source.getPlayer() != null && PermissionManager.isEconomyAdmin(source.getPlayer().getUUID())
                  )
                  .executes(ModCommands::claimRecover)
                  .then(Commands.literal("force").executes(ModCommands::claimRecoverForce))
            )
            .then(
               Commands.literal("restore")
                  .requires(
                     source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
                        || source.getPlayer() != null && PermissionManager.isEconomyAdmin(source.getPlayer().getUUID())
                  )
                  .executes(ModCommands::ffRestoreClaimsPreview)
                  .then(Commands.literal("confirm").executes(ModCommands::ffRestoreClaims))
            )
      );
      dispatcher.register(
         Commands.literal("spawners")
            .then(Commands.literal("sellall").executes(ModCommands::spawnersSellAll))
            .then(Commands.literal("sell").executes(ModCommands::spawnersSellAll))
            .then(
               Commands.literal("recover")
                  .requires(
                     source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
                        || source.getPlayer() != null && PermissionManager.isEconomyAdmin(source.getPlayer().getUUID())
                  )
                  .executes(ModCommands::spawnerRecover)
                  .then(Commands.literal("force").executes(ModCommands::spawnerRecoverForce))
            )
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("raidboss").executes(ModCommands::raidbossHelp))
               .then(
                  ((LiteralArgumentBuilder)Commands.literal("help").executes(ModCommands::raidbossHelp))
                     .then(Commands.argument("boss", StringArgumentType.word()).executes(ctx -> raidbossRecipe(ctx, StringArgumentType.getString(ctx, "boss"))))
               ))
            .then(Commands.literal("active").executes(ModCommands::raidbossActive))
      );
      Predicate<CommandSourceStack> adminOnly = source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
         || source.getPlayer() != null && PermissionManager.isEconomyAdmin(source.getPlayer().getUUID());
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                           "fortuneandfavors"
                        )
                        .requires(adminOnly))
                     .executes(ModCommands::openConfigGui))
                  .then(
                     ((LiteralArgumentBuilder)Commands.literal("config").executes(ModCommands::openConfigGui))
                        .then(
                           Commands.argument("feature", StringArgumentType.word())
                              .executes(ctx -> toggleConfigFeature(ctx, StringArgumentType.getString(ctx, "feature")))
                        )
                  ))
               .then(
                  ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("give").executes(ModCommands::ffGiveList))
                           .then(Commands.literal("list").executes(ModCommands::ffGiveList)))
                        .then(Commands.literal("gui").executes(ModCommands::ffGiveList)))
                     .then(Commands.argument("item", StringArgumentType.greedyString()).executes(ctx -> ffGive(ctx, StringArgumentType.getString(ctx, "item"))))
               ))
            .then(Commands.literal("list").executes(ModCommands::ffGiveList))
      );
      // Built once and hung off `/ff` below, because the same tree is also `/stats scoreboard`:
      // two spellings of one board can never drift apart if there is only one tree, and no
      // `requires` on it - a scoreboard is a thing you look at, so every switch on it is the
      // player's own, which is exactly why it belongs under `/ff` and not only under `/stats`.
      LiteralArgumentBuilder<CommandSourceStack> scoreboardCmd = scoreboardCommand();
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                                       "ff"
                                    )
                                    .executes(ModCommands::ffOpMenu)
                                    .then(
                                       ((LiteralArgumentBuilder)Commands.literal("compassfx").executes(ModCommands::ffCompassFx))
                                          .then(Commands.literal("on").executes(ctx -> ffCompassFxSet(ctx, true)))
                                          .then(Commands.literal("off").executes(ctx -> ffCompassFxSet(ctx, false)))
                                    ))
                                 .then(Commands.literal("version").executes(ModCommands::ffVersion)))
                              .then(Commands.literal("backup").executes(ModCommands::ffBackup)))
                           .then(Commands.literal("leaverealm").executes(ModCommands::ffLeaveRealm)))
                        .then(
                           ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("perf").executes(ModCommands::ffPerf))
                                 .then(Commands.literal("report").executes(ModCommands::ffPerf))
                                 .then(Commands.literal("reset").executes(ModCommands::ffPerfReset)))
                              .then(Commands.literal("on").executes(ctx -> ffPerfToggle(ctx, true)))
                              .then(Commands.literal("off").executes(ctx -> ffPerfToggle(ctx, false)))
                        )
                        .then(
                           ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("dragon").requires(adminOnly))
                                          .executes(ModCommands::ffDragonStatus))
                                       .then(Commands.literal("rift").executes(ModCommands::ffDragonRift)))
                                    .then(Commands.literal("lance").executes(ModCommands::ffDragonLance)))
                                 .then(Commands.literal("finale").executes(ModCommands::ffDragonFinale)))
                              .then(Commands.literal("reset").executes(ModCommands::ffDragonReset)))
                           .then(
                              Commands.literal("phase")
                                 .then(
                                    Commands.argument("phase", IntegerArgumentType.integer(1, 3))
                                       .executes(ctx -> ffDragonPhase(ctx, IntegerArgumentType.getInteger(ctx, "phase")))
                                 )
                           ))
                        .then(
                           ((LiteralArgumentBuilder)Commands.literal("help").executes(ModCommands::ffHelpOverview))
                              .then(
                                 Commands.argument("topic", StringArgumentType.word())
                                    .executes(ctx -> ffHelpTopic(ctx, StringArgumentType.getString(ctx, "topic")))
                              )
                        ))
                     .then(
                        ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("test").requires(adminOnly))
                                 .then(
                                    Commands.literal("corruption")
                                       .then(
                                          Commands.argument("stage", IntegerArgumentType.integer(1, 3))
                                             .executes(ctx -> ffTestCorruption(ctx, IntegerArgumentType.getInteger(ctx, "stage")))
                                       )
                                 ))
                              .then(Commands.literal("illusion").executes(ModCommands::ffTestIllusion)))
                           .then(
                              Commands.literal("puppet")
                                 .then(
                                    Commands.argument("strings", IntegerArgumentType.integer(1, 3))
                                       .executes(ctx -> ffTestPuppet(ctx, IntegerArgumentType.getInteger(ctx, "strings")))
                                 )
                           )
                           .then(Commands.literal("snowqueen").executes(ModCommands::ffTestSnowQueen))
                           .then(Commands.literal("betrayal").executes(ModCommands::ffTestBetrayal))
                           .then(
                              Commands.literal("prison")
                                 .then(Commands.literal("loosebrick").executes(ModCommands::ffTestPrisonLooseBrick))
                                 .then(Commands.literal("skipwait").executes(ModCommands::ffTestPrisonSkipWait))
                           )
                     ))
                  .then(
                     ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("give")
                                    .requires(adminOnly))
                                 .executes(ModCommands::ffGiveList))
                              .then(Commands.literal("list").executes(ModCommands::ffGiveList)))
                           .then(Commands.literal("gui").executes(ModCommands::ffGiveList)))
                        .then(
                           Commands.argument("item", StringArgumentType.greedyString()).executes(ctx -> ffGive(ctx, StringArgumentType.getString(ctx, "item")))
                        )
                  ))
               .then(((LiteralArgumentBuilder)Commands.literal("list").requires(adminOnly)).executes(ModCommands::ffGiveList)))
            .then(
               ((LiteralArgumentBuilder)Commands.literal("config").executes(ModCommands::openConfigGui))
                  .then(
                     Commands.argument("feature", StringArgumentType.word())
                        .executes(ctx -> toggleConfigFeature(ctx, StringArgumentType.getString(ctx, "feature")))
                  )
            )
         .then(
            Commands.literal("title")
               .executes(ModCommands::ffTitleShow)
               .then(Commands.literal("list").executes(ModCommands::ffTitleList))
               .then(
                  Commands.argument("name", StringArgumentType.greedyString())
                     .executes(ctx -> ffTitleSet(ctx, StringArgumentType.getString(ctx, "name")))
               )
         )
         .then(Commands.literal("streak").executes(ModCommands::ffStreak))
         // Same listing as the bare /changelog command, reachable from the /ff
         // namespace so a player who knows /ff version can find its notes too.
         .then(Commands.literal("changelog").executes(ModCommands::changelogShow))
         .then(Commands.literal("changes").executes(ModCommands::changelogShow))
         .then(Commands.literal("challenges").executes(ModCommands::ffChallenges))
         .then(Commands.literal("codex").executes(ModCommands::ffCodex))
         .then(Commands.literal("records").executes(ModCommands::ffRecords))
         // The player's own sidebar - the same builder `/stats scoreboard` registers.
         .then(scoreboardCmd)
         .then(
            Commands.literal("event")
               .requires(adminOnly)
               .executes(ModCommands::ffEventList)
               .then(Commands.literal("stop").executes(ModCommands::ffEventStop))
               .then(Commands.literal("list").executes(ModCommands::ffEventList))
               .then(Commands.literal("mythic").executes(ModCommands::ffEventMythic))
               .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> ffEventStart(ctx, StringArgumentType.getString(ctx, "name"))))
         )
         .then(
            Commands.literal("restore")
               .requires(adminOnly)
               // Bare /ff restore brings back the caller's own lost inventory from a snapshot - the
               // thing a player reaching for the command actually wants. Claim/grave recovery is
               // its own branch (`/ff restore claims`), and a named player still opens the same
               // snapshot screen through the argument path below.
               .executes(ModCommands::ffRestorePreviewSelf)
               .then(Commands.literal("confirm").executes(ModCommands::ffRestoreClaims))
               .then(
                  Commands.literal("self")
                     .executes(ModCommands::ffRestorePreviewSelf)
                     .then(Commands.literal("confirm").executes(ModCommands::ffRestoreSelf))
               )
               .then(
                  Commands.literal("claims")
                     .executes(ModCommands::ffRestoreClaimsPreview)
                     .then(Commands.literal("confirm").executes(ModCommands::ffRestoreClaims))
               )
               .then(
                  Commands.literal("spawners")
                     .executes(ModCommands::ffRestoreSpawners)
                     .then(Commands.literal("confirm").executes(ModCommands::ffRestoreSpawnersForce))
               )
               .then(
                  Commands.literal("machines")
                     .executes(ModCommands::ffRestoreMachines)
                     .then(Commands.literal("confirm").executes(ModCommands::ffRestoreMachinesForce))
               )
            .then(Commands.literal("tier").executes(ModCommands::ffTierDiagnostic))
               .then(
                  Commands.argument("player", EntityArgument.player())
                     .executes(ctx -> ffRestorePreview(ctx, EntityArgument.getPlayer(ctx, "player")))
                     .then(
                        Commands.literal("confirm").executes(ctx -> ffRestore(ctx, EntityArgument.getPlayer(ctx, "player")))
                     )
                     // The additive path, kept for the case a grave holds items the
                     // snapshot does not (a death after the snapshot was taken).
                     .then(
                        Commands.literal("merge").executes(ctx -> ffRestoreMerge(ctx, EntityArgument.getPlayer(ctx, "player")))
                     )
               )
         )
         .then(
            Commands.literal("gems")
               .requires(adminOnly)
               .then(Commands.argument("player", EntityArgument.player())
                  .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                     .executes(ctx -> ffGems(ctx, EntityArgument.getPlayer(ctx, "player"), IntegerArgumentType.getInteger(ctx, "amount")))
                  )
               )
         )
         .then(Commands.literal("claim-soulbound").executes(ModCommands::ffClaimSoulbound))
         .then(Commands.literal("claimsoulbound").executes(ModCommands::ffClaimSoulbound))
         .then(
            ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("vanish")
                     .requires(adminOnly)
                     .executes(ModCommands::ffVanish))
                  .then(Commands.literal("on").executes(ctx -> ffVanishSet(ctx, true)))
                  .then(Commands.literal("off").executes(ctx -> ffVanishSet(ctx, false))))
               .then(Commands.literal("status").executes(ModCommands::ffVanishStatus))
         )
         .then(Commands.literal("afk").executes(ModCommands::ffAfk))
         .then(
            ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("anticheat")
                        .requires(adminOnly))
                     .executes(ModCommands::ffAnticheatPanel))
                  .then(Commands.literal("status").executes(ModCommands::ffAnticheatStatus)))
               .then(Commands.literal("on").executes(ctx -> ffAnticheatSet(ctx, true)))
               .then(Commands.literal("off").executes(ctx -> ffAnticheatSet(ctx, false)))
               .then(
                  ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("op").executes(ModCommands::ffAnticheatStatus))
                        .then(Commands.literal("on").executes(ctx -> ffAnticheatOps(ctx, true))))
                     .then(Commands.literal("off").executes(ctx -> ffAnticheatOps(ctx, false)))
                     // The second consent, and deliberately its own subcommand: seeing a finding
                     // and being kicked for it are different things, and staff who asked for
                     // the first did not ask for the second.
                     .then(
                        ((LiteralArgumentBuilder)Commands.literal("kick")
                              .executes(ctx -> ffAnticheatStatus(ctx)))
                           .then(Commands.literal("on").executes(ctx -> ffAnticheatOpKick(ctx, true)))
                           .then(Commands.literal("off").executes(ctx -> ffAnticheatOpKick(ctx, false)))
                     )
               )
               .then(Commands.literal("testmode").executes(ModCommands::ffAnticheatTestMode))
               .then(Commands.literal("list").executes(ModCommands::ffAnticheatList))
               .then(Commands.literal("compat").executes(ModCommands::ffAnticheatCompat))
               .then(
                  ((LiteralArgumentBuilder)Commands.literal("tune").executes(ModCommands::ffAnticheatTune))
                     .then(
                        Commands.literal("set")
                           .then(
                              Commands.argument("key", StringArgumentType.word())
                                 .then(
                                    Commands.argument("value", DoubleArgumentType.doubleArg())
                                       .executes(ModCommands::ffAnticheatTuneSet)
                                 )
                           )
                     )
                     .then(Commands.literal("reset").then(Commands.argument("key", StringArgumentType.word()).executes(ModCommands::ffAnticheatTuneReset)))
               )
               .then(
                  ((LiteralArgumentBuilder)Commands.literal("banned")
                        .executes(ModCommands::ffAnticheatBanned))
                     // With a name: the same screen, opened on that player's case file. Both
                     // halves exist because they answer different questions - the list is "who
                     // has been flagged", and the case file is "what did this one do".
                     .then(
                        Commands.argument("player", StringArgumentType.word())
                           .executes(ModCommands::ffAnticheatBannedPlayer)
                     )
               )
               // Lifting a punishment is the one moderation action an owner runs from a
               // console, in a state where nothing can open a screen, so it is a plain command
               // with a name argument and no GUI at all.
               .then(
                  ((LiteralArgumentBuilder)Commands.literal("unban")
                        .executes(ModCommands::ffAnticheatBanned))
                     .then(
                        Commands.argument("player", StringArgumentType.word())
                           .executes(ModCommands::ffAnticheatUnban)
                     )
               )
               // Clearing the wait, as opposed to lifting a punishment: a kick window, a live
               // timeout and the automatic ladder are all "waiting" rather than "punished", and
               // an owner asked to stop a body waiting should not have to restart the server or
               // reach for unban, which is a different decision about a different thing.
               .then(
                  Commands.literal("clear")
                     .then(Commands.argument("player", StringArgumentType.word()).executes(ModCommands::ffAnticheatClear))
               )
               // The kick, from the console or from staff, with the reason as words. It is
               // here rather than only inside the spectate kit because the moment somebody
               // wants to kick a cheater is usually the moment they are not watching them
               // through a kit - and because a kick with no re-entry window is a disconnect
               // the player can undo by reconnecting, which is what "the kick does not work"
               // described.
               .then(
                  Commands.literal("kick")
                     .then(
                        Commands.argument("player", StringArgumentType.word())
                           .executes(ctx -> ffAnticheatKick(ctx, "no reason given"))
                           .then(
                              Commands.argument("reason", StringArgumentType.greedyString())
                                 .executes(ctx -> ffAnticheatKick(ctx, StringArgumentType.getString(ctx, "reason")))
                           )
                     )
               )
               .then(
                  ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("alerts")
                           .executes(ModCommands::ffAnticheatStatus))
                        .then(Commands.literal("on").executes(ctx -> ffAnticheatAlerts(ctx, true))))
                     .then(Commands.literal("off").executes(ctx -> ffAnticheatAlerts(ctx, false)))
               )
               .then(
                  ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("failsafe")
                           .executes(ModCommands::ffAnticheatStatus))
                        .then(Commands.literal("on").executes(ctx -> ffAnticheatFailsafe(ctx, true))))
                     .then(Commands.literal("off").executes(ctx -> ffAnticheatFailsafe(ctx, false)))
               )
               .then(Commands.literal("info").then(Commands.argument("player", EntityArgument.player()).executes(ModCommands::ffAnticheatInfo)))
               .then(Commands.literal("watch").then(Commands.argument("player", EntityArgument.player()).executes(ModCommands::ffAnticheatWatch)))
               .then(Commands.literal("unwatch").then(Commands.argument("player", EntityArgument.player()).executes(ModCommands::ffAnticheatUnwatch)))
               .then(Commands.literal("spectate").then(Commands.argument("player", EntityArgument.player()).executes(ModCommands::ffAnticheatSpectate)))
               .then(Commands.literal("unspectate").executes(ModCommands::ffAnticheatUnspectate))
               .then(
                  ((LiteralArgumentBuilder)Commands.literal("simulate").executes(ModCommands::ffAnticheatSimulateSelf))
                     .then(Commands.argument("player", EntityArgument.player()).executes(ModCommands::ffAnticheatSimulate))
               )
               .then(
                  ((LiteralArgumentBuilder)Commands.literal("why").executes(ModCommands::ffAnticheatWhySelf))
                     .then(Commands.argument("player", EntityArgument.player()).executes(ModCommands::ffAnticheatWhy))
               )
               .then(Commands.literal("probe").executes(ModCommands::ffAnticheatProbe))
               .then(
                  Commands.literal("exempt")
                     .then(
                        Commands.argument("player", EntityArgument.player())
                           .then(
                              Commands.argument("check", StringArgumentType.word())
                                 .then(
                                    Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                                       .executes(ModCommands::ffAnticheatExempt)
                                 )
                           )
                     )
               )
               .then(
                  Commands.literal("unexempt")
                     .then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("check", StringArgumentType.word()).executes(ModCommands::ffAnticheatUnexempt)))
               )
               .then(
                  Commands.literal("review")
                     .then(
                        Commands.argument("player", EntityArgument.player())
                           .then(
                              Commands.argument("check", StringArgumentType.word())
                                 .then(Commands.literal("confirm").executes(ctx -> ffAnticheatReview(ctx, false)))
                                 .then(Commands.literal("false").executes(ctx -> ffAnticheatReview(ctx, true)))
                           )
                     )
               )
         ))
      );       // TEMP dev-only: wither rework smoke-test driver (works from console, no player needed).
       dispatcher.register((LiteralArgumentBuilder)Commands.literal("wtest")
          .requires(adminOnly)
          .executes(ModCommands::wtestSpawn)
          .then(Commands.literal("spawn").executes(ModCommands::wtestSpawn))
          .then(Commands.literal("dmg").then(Commands.argument("amount", IntegerArgumentType.integer(1)).executes(ModCommands::wtestDmg)))
          .then(Commands.literal("info").executes(ModCommands::wtestInfo)));
       dispatcher.register((LiteralArgumentBuilder)Commands.literal("deliver").executes(ModCommands::openDeliverGui));
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("gemshop").executes(ModCommands::openGemShop));
      // /tags is now the title & tag equip menu (records live in /menu → Records).
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("tags").executes(ModCommands::openTagsMenu));
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("news").executes(ModCommands::newsNow));
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("changelog").executes(ModCommands::changelogShow));
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("mine").executes(ModCommands::openGuildMineGui));
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("rewards").executes(ModCommands::openDailyRewardsGui));
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("daily").executes(ModCommands::openDailyRewardsGui));
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("prison").executes(ModCommands::prisonOpen))
               .then(Commands.literal("leave").executes(ModCommands::prisonLeave)))
            .then(Commands.literal("rankup").executes(ModCommands::prisonRankUp))
            .then(Commands.literal("sell").executes(ModCommands::prisonSell))
      );
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("rankup").executes(ModCommands::prisonRankUp));
      LiteralArgumentBuilder<CommandSourceStack> expeditionCmd =
         Commands.literal("expedition").executes(ModCommands::openExpeditionGui);
      expeditionCmd.then(Commands.literal("shop").executes(ModCommands::openExpeditionShop));
      dispatcher.register(expeditionCmd);
      LiteralArgumentBuilder<CommandSourceStack> expeditionsCmd =
         Commands.literal("expeditions").executes(ModCommands::openExpeditionGui);
      expeditionsCmd.then(Commands.literal("shop").executes(ModCommands::openExpeditionShop));
      dispatcher.register(expeditionsCmd);
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("cosmetics").executes(ModCommands::openCosmeticsGui));
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("runes").executes(ModCommands::openRuneGui));
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("rune").executes(ModCommands::openRuneGui));
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("leaderboard")
               .executes(ModCommands::openLeaderboardGui))
            .then(Commands.literal("lb").executes(ModCommands::openLeaderboardGui))
      );
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("lb").executes(ModCommands::openLeaderboardGui));
      LiteralCommandNode<CommandSourceStack> economy = dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                                                "economy"
                                             )
                                             .requires(
                                                source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
                                                   || source.getPlayer() != null && PermissionManager.isEconomyAdmin(source.getPlayer().getUUID())
                                             ))
                                          .then(
                                             Commands.literal("give")
                                                .then(
                                                   Commands.argument("player", EntityArgument.player())
                                                      .then(
                                                         Commands.argument("amount", LongArgumentType.longArg(1L))
                                                            .executes(
                                                               ctx -> economyGive(
                                                                  ctx, EntityArgument.getPlayer(ctx, "player"), LongArgumentType.getLong(ctx, "amount"), false
                                                               )
                                                            )
                                                      )
                                                )
                                          ))
                                       .then(
                                          Commands.literal("take")
                                             .then(
                                                Commands.argument("player", EntityArgument.player())
                                                   .then(
                                                      Commands.argument("amount", LongArgumentType.longArg(1L))
                                                         .executes(
                                                            ctx -> economyGive(
                                                               ctx, EntityArgument.getPlayer(ctx, "player"), LongArgumentType.getLong(ctx, "amount"), true
                                                            )
                                                         )
                                                   )
                                             )
                                       ))
                                    .then(
                                       Commands.literal("set")
                                          .then(
                                             Commands.argument("player", EntityArgument.player())
                                                .then(
                                                   Commands.argument("amount", LongArgumentType.longArg(0L))
                                                      .executes(
                                                         ctx -> economySet(
                                                            ctx, EntityArgument.getPlayer(ctx, "player"), LongArgumentType.getLong(ctx, "amount")
                                                         )
                                                      )
                                                )
                                          )
                                    ))
                                 .then(
                                    Commands.literal("balance")
                                       .then(
                                          Commands.argument("player", EntityArgument.player())
                                             .executes(ctx -> economyBalance(ctx, EntityArgument.getPlayer(ctx, "player")))
                                       )
                                 ))
                              .then(
                                 Commands.literal("reset")
                                    .then(
                                       Commands.argument("player", EntityArgument.player())
                                          .executes(ctx -> economyReset(ctx, EntityArgument.getPlayer(ctx, "player")))
                                    )
                              ))
                           .then(Commands.literal("top").executes(ModCommands::economyTop)))
                        .then(Commands.literal("help").executes(ModCommands::economyHelp)))
                     .then(Commands.literal("reload").executes(ModCommands::economyReload)))
                  .then(Commands.literal("test").then(Commands.literal("auction").executes(ModCommands::economyTestAuction))))
               .then(
                  ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("boss")
                           .then(
                              Commands.literal("spawn")
                                 .then(
                                    ((RequiredArgumentBuilder)Commands.argument("mob", StringArgumentType.word())
                                          .executes(ctx -> bossSpawn(ctx, StringArgumentType.getString(ctx, "mob"), 300.0)))
                                       .then(
                                          Commands.argument("hp", DoubleArgumentType.doubleArg(10.0, 1000000.0))
                                             .executes(ctx -> bossSpawn(ctx, StringArgumentType.getString(ctx, "mob"), DoubleArgumentType.getDouble(ctx, "hp")))
                                       )
                                 )
                           ))
                        .then(Commands.literal("clear").executes(ModCommands::bossClear))
                        .then(Commands.literal("wipe").executes(ModCommands::bossWipe)))
                     .then(Commands.literal("list").executes(ModCommands::bossList))
               ))
            .then(
               ((LiteralArgumentBuilder)Commands.literal("price")
                     .then(
                        Commands.literal("multiplier")
                           .then(
                              Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.1))
                                 .executes(ctx -> economyPriceMultiplier(ctx, DoubleArgumentType.getDouble(ctx, "multiplier")))
                           )
                     ))
                  .then(
                     Commands.argument("item", ItemArgument.item(context))
                        .then(
                           Commands.argument("price", LongArgumentType.longArg(1L))
                              .executes(
                                 ctx -> economyPriceOverride(
                                    ctx, (Item)ItemArgument.getItem(ctx, "item").item().value(), LongArgumentType.getLong(ctx, "price")
                                 )
                              )
                        )
                  )
            )
      );
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("eco").redirect(economy));
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("chestshop")
               .then(
                  ((LiteralArgumentBuilder)Commands.literal("price")
                        .then(
                           Commands.literal("all")
                              .then(
                                 Commands.argument("price", LongArgumentType.longArg(1L))
                                    .executes(ctx -> setChestShopPrice(ctx, null, LongArgumentType.getLong(ctx, "price")))
                              )
                        ))
                     .then(
                        Commands.argument("item", ItemArgument.item(context))
                           .then(
                              Commands.argument("price", LongArgumentType.longArg(1L))
                                 .executes(
                                    ctx -> setChestShopPrice(
                                       ctx, (Item)ItemArgument.getItem(ctx, "item").item().value(), LongArgumentType.getLong(ctx, "price")
                                    )
                                 )
                           )
                     )
               ))
            .then(
               ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("currency")
                        .then(Commands.literal("cash").executes(ctx -> setChestShopCurrency(ctx, "cash"))))
                     .then(Commands.literal("token").executes(ctx -> setChestShopCurrency(ctx, "fortuneandfavors:token"))))
                  .then(
                     Commands.argument("item", ItemArgument.item(context))
                        .executes(ctx -> setChestShopCurrency(ctx, itemId((Item)ItemArgument.getItem(ctx, "item").item().value())))
                  )
            )
            .then(Commands.literal("toggle").executes(ModCommands::toggleChestShop))
            .then(Commands.literal("mode").executes(ModCommands::toggleChestShop))
            .then(
               ((LiteralArgumentBuilder)Commands.literal("ledger").executes(ctx -> showChestShopLedger(ctx, 10)))
                  .then(
                     Commands.argument("count", IntegerArgumentType.integer(1, ChestShopManager.LEDGER_LIMIT))
                        .executes(ctx -> showChestShopLedger(ctx, IntegerArgumentType.getInteger(ctx, "count")))
                  )
            )
            .then(Commands.literal("alerts").executes(ModCommands::toggleChestShopAlerts))
            .then(
               Commands.literal("delete")
                  .then(Commands.literal("confirm").executes(ctx -> deleteChestShop(ctx, true)))
                  .executes(ctx -> deleteChestShop(ctx, false))
            )
      );
      LiteralCommandNode<CommandSourceStack> auctionCmd = dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                                 "auction"
                              )
                              .executes(ModCommands::openAuctionGui))
                           .then(Commands.literal("list").executes(ModCommands::auctionList))
                           .then(Commands.literal("mine").executes(ModCommands::auctionMine))
                           .then(Commands.literal("bids").executes(ModCommands::auctionBids))
                           .then(
                              Commands.literal("search")
                                 .then(
                                    Commands.argument("text", StringArgumentType.greedyString())
                                       .executes(ctx -> auctionSearch(ctx, StringArgumentType.getString(ctx, "text")))
                                 )
                           ))
                        .then(Commands.literal("claim").executes(ModCommands::collect)))
                     .then(
                        Commands.literal("bid")
                           .then(
                              Commands.argument("id", IntegerArgumentType.integer(1))
                                 .then(
                                    Commands.argument("amount", LongArgumentType.longArg(1L))
                                       .executes(ctx -> auctionBid(ctx, IntegerArgumentType.getInteger(ctx, "id"), LongArgumentType.getLong(ctx, "amount")))
                                 )
                           )
                     ))
                  .then(
                     Commands.literal("buy")
                        .then(
                           Commands.argument("id", IntegerArgumentType.integer(1)).executes(ctx -> auctionBuy(ctx, IntegerArgumentType.getInteger(ctx, "id")))
                        )
                  ))
               .then(
                  Commands.literal("cancel")
                     .then(
                        Commands.argument("id", IntegerArgumentType.integer(1)).executes(ctx -> auctionCancel(ctx, IntegerArgumentType.getInteger(ctx, "id")))
                     )
               ))
            .then(
               ((LiteralArgumentBuilder)Commands.literal("sell")
                     .then(
                        Commands.literal("bid")
                           .then(
                              ((RequiredArgumentBuilder)Commands.argument("start", LongArgumentType.longArg(1L))
                                    .executes(
                                       ctx -> auctionCreate(ctx, "bid", LongArgumentType.getLong(ctx, "start"), 1L, ModConfig.auctionMinutes() * 1200L, "cash")
                                    ))
                                 .then(
                                    ((RequiredArgumentBuilder)Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
                                          .executes(
                                             ctx -> auctionCreate(
                                                ctx,
                                                "bid",
                                                LongArgumentType.getLong(ctx, "start"),
                                                1L,
                                                IntegerArgumentType.getInteger(ctx, "minutes") * 1200L,
                                                "cash"
                                             )
                                          ))
                                       .then(
                                          Commands.argument("currency", StringArgumentType.word())
                                             .executes(
                                                ctx -> auctionCreate(
                                                   ctx,
                                                   "bid",
                                                   LongArgumentType.getLong(ctx, "start"),
                                                   1L,
                                                   IntegerArgumentType.getInteger(ctx, "minutes") * 1200L,
                                                   StringArgumentType.getString(ctx, "currency")
                                                )
                                             )
                                       )
                                 )
                           )
                     ))
                  .then(
                     Commands.literal("fixed")
                        .then(
                           ((RequiredArgumentBuilder)Commands.argument("price", LongArgumentType.longArg(1L))
                                 .executes(ctx -> auctionCreate(ctx, "fixed", LongArgumentType.getLong(ctx, "price"), 1L, 0L, "cash")))
                              .then(
                                 Commands.argument("currency", StringArgumentType.word())
                                    .executes(
                                       ctx -> auctionCreate(
                                          ctx, "fixed", LongArgumentType.getLong(ctx, "price"), 1L, 0L, StringArgumentType.getString(ctx, "currency")
                                       )
                                    )
                              )
                        )
                  )
            )
      );
      dispatcher.register((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("au").redirect(auctionCmd)).executes(ModCommands::openAuctionGui));
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("skills").executes(ModCommands::openSkillsGui));
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("menu").executes(ModCommands::openMenuHub));
      // The player's own sidebar, one tree reached from two roots: `/stats scoreboard` (where it
      // has always lived) and `/ff scoreboard` (the spelling a player already knows, since `/ff`
      // is where the rest of this mod answers from). `/stats` on its own is the show command,
      // because the commonest thing a player wants from it is to see their board - and the
      // commonest question after that is how to change it, so every reply names the edit screen.
      dispatcher.register(
         Commands.literal("stats").executes(ModCommands::statsScoreboardShow).then(scoreboardCmd)
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                              "NiceKeepInventory"
                           )
                           .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)))
                        .executes(ModCommands::niceKeepInventoryToggle))
                     .then(Commands.literal("on").executes(ctx -> niceKeepInventorySet(ctx, true))))
                  .then(Commands.literal("off").executes(ctx -> niceKeepInventorySet(ctx, false))))
               .then(Commands.literal("status").executes(ModCommands::niceKeepInventoryStatus)))
            .then(
               ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("claimother").executes(ctx -> niceKeepInventoryClaimOther(ctx, null)))
                     .then(Commands.literal("on").executes(ctx -> niceKeepInventoryClaimOther(ctx, true))))
                  .then(Commands.literal("off").executes(ctx -> niceKeepInventoryClaimOther(ctx, false)))
            )
      );
      DuelCommands.register(dispatcher);
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("mapedit")
                     .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)))
                  .executes(ctx -> mapEditList(ctx)))
               .then(Commands.literal("restore").executes(ctx -> mapRestore(ctx, null))))
            .then(
               ((RequiredArgumentBuilder)Commands.argument("mode", StringArgumentType.word())
                     .executes(ctx -> mapEdit(ctx, StringArgumentType.getString(ctx, "mode"))))
                  .then(Commands.literal("restore").executes(ctx -> mapRestore(ctx, StringArgumentType.getString(ctx, "mode"))))
            )
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("map").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)))
            .then(
               ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("edit").executes(ctx -> mapEditList(ctx)))
                     .then(Commands.literal("restore").executes(ctx -> mapRestore(ctx, null))))
                  .then(
                     ((RequiredArgumentBuilder)Commands.argument("mode", StringArgumentType.word())
                           .executes(ctx -> mapEdit(ctx, StringArgumentType.getString(ctx, "mode"))))
                        .then(Commands.literal("restore").executes(ctx -> mapRestore(ctx, StringArgumentType.getString(ctx, "mode"))))
                  )
            )
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("mapexport")
               .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)))
            .executes(ctx -> mapExport(ctx))
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("export")
               .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)))
            .executes(ctx -> mapExport(ctx))
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("mapexit")
               .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)))
            .executes(ctx -> mapExit(ctx))
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("exit")
               .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)))
            .executes(ctx -> mapExit(ctx))
      );
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                                             "guild"
                                          )
                                          .executes(ModCommands::guildMenu))
                                       .then(
                                          Commands.literal("create")
                                             .then(
                                                Commands.argument("name", StringArgumentType.greedyString())
                                                   .executes(ctx -> guildCreate(ctx, StringArgumentType.getString(ctx, "name")))
                                             )
                                       ))
                                    .then(
                                       Commands.literal("join")
                                          .then(
                                             Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> guildJoin(ctx, StringArgumentType.getString(ctx, "name")))
                                          )
                                    ))
                                 .then(
                                    Commands.literal("invite")
                                       .then(
                                          Commands.argument("player", EntityArgument.player())
                                             .executes(ctx -> guildInvite(ctx, EntityArgument.getPlayer(ctx, "player")))
                                       )
                                 ))
                              .then(
                                 Commands.literal("leave")
                                    .executes(ModCommands::guildLeave)
                                    .then(Commands.literal("confirm").executes(ModCommands::guildLeaveConfirm))
                                    .then(Commands.literal("cancel").executes(ModCommands::guildLeaveCancel))))
                           .then(Commands.literal("disband").executes(ModCommands::guildDisband)))
                        .then(Commands.literal("settings").executes(ModCommands::guildSettings)))
                     .then(Commands.literal("skills").executes(ModCommands::guildSkills)))
                  .then(Commands.literal("perks").executes(ModCommands::guildSkills)))
               .then(
                  Commands.literal("motd")
                     .then(
                        Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> guildMotd(ctx, StringArgumentType.getString(ctx, "text")))
                     )
               ))
            .then(
               ((LiteralArgumentBuilder)Commands.literal("info").executes(ModCommands::guildInfo))
                  .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> guildInfo(ctx, EntityArgument.getPlayer(ctx, "player"))))
            )
            .then(
               Commands.literal("declarewar")
                  .then(
                     Commands.argument("guild", StringArgumentType.greedyString())
                        .executes(ctx -> guildDeclareWar(ctx, StringArgumentType.getString(ctx, "guild")))
                  )
            )
            .then(Commands.literal("peace").executes(ModCommands::guildPeace))
            .then(
               Commands.literal("mine")
                  .executes(ModCommands::openGuildMineGui)
                  .then(
                     Commands.argument("amount", LongArgumentType.longArg(1L))
                        .executes(ctx -> guildMineDeposit(ctx, LongArgumentType.getLong(ctx, "amount")))
                  )
                  .then(Commands.literal("notify").executes(ModCommands::guildMineNotify))
            )
            .then(
               Commands.literal("promote")
                  .then(
                     Commands.argument("member", StringArgumentType.word())
                        .executes(ctx -> guildPromote(ctx, StringArgumentType.getString(ctx, "member")))
                  )
            )
            .then(
               Commands.literal("demote")
                  .then(
                     Commands.argument("member", StringArgumentType.word())
                        .executes(ctx -> guildDemote(ctx, StringArgumentType.getString(ctx, "member")))
                  )
            )
            .then(
               Commands.literal("mail")
                  .executes(ModCommands::openGuildMailGui)
                  .then(
                     Commands.literal("send")
                        .then(
                           Commands.argument("member", StringArgumentType.word())
                              .executes(ctx -> guildMailSend(ctx, StringArgumentType.getString(ctx, "member"), null))
                                 .then(
                                    Commands.argument("subject", StringArgumentType.greedyString())
                                       .executes(ctx -> guildMailSend(ctx, StringArgumentType.getString(ctx, "member"), StringArgumentType.getString(ctx, "subject")))
                                 )
                        )
                  )
            )
      );
   }

   private static int guildDeclareWar(CommandContext<CommandSourceStack> ctx, String guildName) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = GuildManager.declareWar(player, guildName);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      } else {
         GuildManager.save(((CommandSourceStack)ctx.getSource()).getServer());
         return 1;
      }
   }

   private static int guildPeace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = GuildManager.peace(player);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      } else {
         GuildManager.save(((CommandSourceStack)ctx.getSource()).getServer());
         return 1;
      }
   }

   private static int ffRestoreSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      return ffRestore(ctx, null);
   }

   /** /ff restore (no argument): opens the confirmation GUI on yourself. */
   private static int ffRestorePreviewSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer source = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.menu.RestoreMenu.open(source, source);
      return 1;
   }

   /** /ff claim-soulbound: immediately restores any soulbound items captured at
    *  death (useful after relogging before a respawn, or after a restart). */
   private static int ffClaimSoulbound(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      int given = com.fortuneandfavors.economy.AdvancedEnchantments.claimSoulbound(player);
      if (given > 0) {
         Chat.raw(player, "§dClaimed §f" + given + " soulbound item" + (given == 1 ? "" : "s") + "§d from your death stash.");
      } else {
         Chat.msg(player, "&7You have no soulbound items waiting.");
      }
      return 1;
   }

   /** Dry run: shows what /ff restore <player> confirm would bring back without
    *  touching the player's inventory, so admins don't wipe a live inventory by
    *  accident. */
   private static int ffRestorePreview(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
      ServerPlayer source = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (source != null) {
         com.fortuneandfavors.menu.RestoreMenu.open(source, target);
         return 1;
      }
      return ffRestorePreviewText(ctx, target);
   }

   /** Console has no GUI, so it still gets the plain-text dry run. */
   private static int ffRestorePreviewText(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
      ServerPlayer source = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      int graves = com.fortuneandfavors.NiceKeepInventoryManager.previewGraveCount(target);
      int snapshot = com.fortuneandfavors.util.LastInventoryHolder.previewCount(target);
      Chat.msg(source, "&ePreview of &f/ff restore &b" + target.getName().getString() + "&e:");
      Chat.msg(source, "&7  Graves: &f" + graves + "&7 item(s) - returned to inventory, graves removed.");
      if (snapshot >= 0) {
         Chat.msg(source, "&7  Snapshot: &f" + snapshot + "&7 item(s) - &cthis REPLACES the inventory.");
         Chat.msg(source, "&7  Use &f/ff restore " + target.getName().getString() + " merge&7 to add instead of replace.");
      } else {
         Chat.msg(source, "&7  Snapshot: &cnone&7 (no recent inventory state on file).");
         Chat.msg(source, "&7  Only graves can come back - &fconfirm&7 will merge them in.");
      }
      Chat.msg(source, "&eRun &f/ff restore " + target.getName().getString() + " confirm&e to apply.");
      return 1;
   }

   private static int ffRestore(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
      ServerPlayer source = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      // /ff restore <player> confirm is the "make this player whole again" call, so
      // it overwrites. It used to merge, which left a half-broken inventory sitting
      // underneath the restored one - the worst of both states. The additive path is
      // still there, spelled out, as `merge`.
      performRestoreOverwrite(source, target, 0);
      return 1;
   }

   private static int ffRestoreMerge(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
      ServerPlayer source = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      performRestore(source, target);
      return 1;
   }

   /** The single restore path, shared by the GUI and the command so the two can
    *  never drift apart. Three sources come back, in this order:
    *   1. every unclaimed NKI grave,
    *   2. anything the Snow Queen's realm still has sealed for the player,
    *   3. the pre-death inventory snapshot.
    *  Nothing the player is already carrying is ever removed. Returns the number
    *  of item(s) handed back, or -1 when there was nothing to restore. */
   public static int performRestore(ServerPlayer source, ServerPlayer target) {
      return performRestore(source, target, 0);
   }

   /**
    * As {@link #performRestore(ServerPlayer, ServerPlayer)}, but restores the
    * chosen entry from the target's snapshot history - 0 is the newest, and the
    * older ones are the states from before a later death overwrote it.
    */
   /**
    * Puts one snapshot back over the top of whatever the target is holding.
    *
    * <p>The additive {@link #performRestore(ServerPlayer, ServerPlayer, int)} is the
    * right answer when the question is "give back what was lost" - it can only ever
    * add, so it cannot destroy a grave's contents or a partly-recovered kit. It is
    * the wrong answer when the inventory itself is the problem: a player who came
    * back from a broken duel still holding a PvP kit wants their real inventory
    * back, not that kit with their real inventory stacked around it. This replaces
    * the slots outright.
    *
    * <p>Graves are skipped on purpose. A grave is the fallback for a loss with no
    * snapshot on file; when there <i>is</i> a pre-loss snapshot, re-adding the grave
    * loot on top of it would duplicate every item the snapshot already contains.
    * When no snapshot exists the additive path is used instead, because the grave is
    * then the only record left.
    *
    * @return the number of items written, or -1 when there was nothing to overwrite with
    */
   public static int performRestoreOverwrite(ServerPlayer source, ServerPlayer target, int snapshotIndex) {
      ServerPlayer to = target != null ? target : source;
      if (com.fortuneandfavors.util.LastInventoryHolder.previewCount(to) < 0) {
         // No snapshot at all: the additive path is the only thing that can help.
         return performRestore(source, target, snapshotIndex);
      }
      int written = com.fortuneandfavors.util.LastInventoryHolder.restoreOverwrite(to, snapshotIndex);
      if (written < 0) {
         Chat.msg(source, "&cNo saved inventory at that position for &f" + to.getName().getString() + "&c - try &f/ff restore " + to.getName().getString() + " merge&c.");
         return -1;
      }
      Chat.msg(source, "&aOverwrote the inventory of &f" + to.getName().getString() + "&a with &f" + written + "&a saved item(s).");
      Chat.msg(to, "&aYour inventory was restored by an admin - &f" + written + "&a item(s).");
      return written;
   }

   public static int performRestore(ServerPlayer source, ServerPlayer target, int snapshotIndex) {
      ServerPlayer to = target != null ? target : source;
      int given = -1;
      // Graves first: /ff restore is the escape hatch for EVERYTHING a player
      // lost - unclaimed NKI graves AND the pre-death snapshot. Chests can be
      // swept by bugs, but their inventory is safe one way or the other.
      if (com.fortuneandfavors.NiceKeepInventoryManager.isEnabled()) {
         int fromGraves = com.fortuneandfavors.NiceKeepInventoryManager.restorePlayerInventory(to, to.level().getServer());
         given = Math.max(given, fromGraves);
      }
      // A death inside the Snow Queen's realm seals the inventory in an ice cube
      // instead of a grave, so without this the command reported "nothing to
      // restore" for realm deaths - and for any realm that was torn down while
      // the player was offline.
      java.util.List<ItemStack> sealed = com.fortuneandfavors.economy.BossManager.reclaimSealedDeposit(to);
      if (sealed != null) {
         int fromRealm = 0;
         for (ItemStack s : sealed) {
            if (s == null || s.isEmpty()) {
               continue;
            }
            fromRealm += s.getCount();
            com.fortuneandfavors.util.InventoryHelper.giveOrDrop(to, s.copy());
         }
         if (fromRealm > 0) {
            given = given < 0 ? fromRealm : given + fromRealm;
         }
      }
      int fromSnapshot = com.fortuneandfavors.util.LastInventoryHolder.restore(to, snapshotIndex);
      if (fromSnapshot >= 0) {
         given = given < 0 ? fromSnapshot : given + fromSnapshot;
      }
      if (given < 0) {
         Chat.msg(source, "&cNothing to restore for &f" + to.getName().getString() + "&c - no graves and no saved inventory found.");
      } else if (target != null) {
         Chat.msg(source, "&aRestored &f" + given + "&a item(s) to &f" + to.getName().getString() + "&a from graves and pre-death snapshots.");
         Chat.msg(to, "&aYour lost items were restored by an admin - &f" + given + "&a item(s).");
      } else {
         Chat.msg(source, "&aRestored &f" + given + "&a item(s) from graves and your last pre-death inventory.");
      }
      return given;
   }

   private static int ffGems(CommandContext<CommandSourceStack> ctx, ServerPlayer target, int amount) throws CommandSyntaxException {
      ServerPlayer source = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.economy.EconomyManager.addGems(target.getUUID(), amount);
      long newBal = com.fortuneandfavors.economy.EconomyManager.gemBalance(target.getUUID());
      Chat.msg(source, "&aGave &5" + amount + " gem" + (amount == 1 ? "" : "s") + "&a to &f" + target.getName().getString() + "&a. New balance: &5" + newBal + " gem" + (newBal == 1 ? "" : "s"));
      Chat.msg(target, "&5You received " + amount + " gem" + (amount == 1 ? "" : "s") + "&5 from an admin!");
      return 1;
   }

   private static int openGuildMineGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.menu.GuildMineMenu.open(player);
      return 1;
   }

   private static int openDailyRewardsGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.menu.DailyRewardsMenu.open(player);
      return 1;
   }

   private static int openExpeditionGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.menu.ExpeditionMenu.open(player);
      return 1;
   }

   /**
    * The permanent Expedition Shop. Open whether or not the player is in a site, because what it
    * sells is the sum of the runs rather than anything for one of them.
    */
   private static int openExpeditionShop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.menu.ExpeditionShopMenu.open(player);
      return 1;
   }

   private static int openCosmeticsGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.menu.CosmeticsMenu.open(player);
      return 1;
   }

   private static int openBankGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.menu.BankMenu.open(player);
      return 1;
   }

   private static int openStocksGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.menu.StockMarketMenu.open(player);
      return 1;
   }

   private static int openLeaderboardGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.menu.LeaderboardMenu.open(player);
      return 1;
   }

   private static int openRuneGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.menu.RuneMenu.open(player);
      return 1;
   }

   private static int guildMineDeposit(CommandContext<CommandSourceStack> ctx, long amount) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = GuildManager.mineDeposit(player, amount);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      }
      return 1;
   }

   private static int guildMineNotify(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String msg = GuildManager.mineNotifyToggle(player);
      Chat.msg(player, msg.startsWith("Mine") ? "&a" + msg : "&c" + msg);
      return 1;
   }

   private static int openDeliverGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.menu.DeliverMenu.open(player);
      return 1;
   }

   private static int openTagsMenu(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "tags")) {
         Chat.msg(player, "&cTags & titles are disabled on this server.");
         return 0;
      }
      com.fortuneandfavors.menu.TagsMenu.open(player);
      return 1;
   }

   private static int tagsOverview(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      Chat.raw(player, "§6§lSERVER RECORDS");
      for (java.util.Map.Entry<String, String> e : com.fortuneandfavors.economy.FirstEverRecordManager.all().entrySet()) {
         Chat.raw(player, " §5★ §f" + com.fortuneandfavors.economy.FirstEverRecordManager.displayForNews(e.getKey()) + "§7 - " + e.getValue());
      }
      if (com.fortuneandfavors.economy.FirstEverRecordManager.all().isEmpty()) {
         Chat.raw(player, " §7No first-ever records yet - go make history!");
      }
      String tag = com.fortuneandfavors.economy.TagManager.getTag(player.getUUID()) == null
         ? "§7none"
         : "§b[" + com.fortuneandfavors.economy.TagManager.getTag(player.getUUID()).text() + "§b]";
      Chat.raw(player, "§6Your tag: " + tag + "§7 - set it with §f/tag set <text>§7.");
      String title = com.fortuneandfavors.economy.TitleManager.activeTitle(player.getUUID());
      Chat.raw(player, "§6Your title: " + (title == null ? "§7none" : "§6[" + title + "§6]") + "§7 - see all with §f/ff title list§7.");
      return 1;
   }

   private static int newsNow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      com.fortuneandfavors.menu.NewsMenu.open(player);
      return 1;
   }

   private static int changelogShow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      try (java.io.InputStream is = ModCommands.class.getResourceAsStream("/data/fortuneandfavors/changelog.txt")) {
         if (is == null) {
            Chat.msg(player, "&cThe changelog file is missing from the mod jar.");
            return 0;
         }
         String text = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
         Chat.raw(player, "§6§l─ Fortune & Favors changelog ─");
         String[] lines = text.split("\r?\n");
         int shown = 0;
         for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty() || l.equals("---") || l.startsWith("```")) {
               continue;
            }
            if (l.startsWith("#")) {
               Chat.raw(player, "§e§l" + l.replaceFirst("^#+\\s*", "") + "§r");
            } else if (l.startsWith("- ")) {
               Chat.raw(player, " §7• §f" + l.substring(2));
            } else {
               Chat.raw(player, " §8" + l);
            }
            if (++shown >= 60) {
               Chat.raw(player, "§8... and more - the full log lives in the repo's CHANGELOG.md.");
               break;
            }
         }
      } catch (java.io.IOException e) {
         Chat.msg(player, "&cCouldn't read the changelog: " + e.getMessage());
      }
      return 1;
   }

   private static int ffTitleShow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String title = com.fortuneandfavors.economy.TitleManager.activeTitle(player.getUUID());
      if (title == null) {
         Chat.msg(player, "&7You don't have an active title. See unlocked titles with &e/ff title list&7.");
      } else {
         Chat.msg(player, "&7Active title: &6[" + title + "&6]");
      }
      return 1;
   }

   private static int ffTitleList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      java.util.Set<String> titles = com.fortuneandfavors.economy.TitleManager.unlockedTitles(player.getUUID());
      if (titles.isEmpty()) {
         Chat.msg(player, "&7You haven't unlocked any titles yet. Kill bosses, win duels and keep logging in!");
      } else {
         Chat.msg(player, "&7Your titles: &6" + String.join("§7, §6", titles));
         Chat.msg(player, "&7Equip one with &e/ff title <name>&7.");
      }
      return 1;
   }

   private static int ffTitleSet(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!com.fortuneandfavors.economy.TitleManager.setActive(player, name)) {
         Chat.msg(player, "&cYou haven't unlocked the title \"&f" + name + "&c\". Check /ff title list.");
         return 0;
      }
      com.fortuneandfavors.economy.TagManager.refreshTabList(((CommandSourceStack)ctx.getSource()).getServer());
      return 1;
   }

   private static int ffStreak(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      Chat.msg(
         player,
         "&7Login streak: day &f"
            + com.fortuneandfavors.economy.DailyLoginStreakManager.dayOf(player.getUUID())
            + "&7 · freezes &f"
            + com.fortuneandfavors.economy.DailyLoginStreakManager.freezesOf(player.getUUID())
            + "&7/3"
      );
      Chat.msg(
         player,
         "&7Duel wins &f"
            + com.fortuneandfavors.economy.StreakTrackerManager.duelWins(player.getUUID())
            + "&7 · boss streak &f"
            + com.fortuneandfavors.economy.StreakTrackerManager.bossStreak(player.getUUID())
            + "&7 · bounty level &f"
            + com.fortuneandfavors.economy.StreakTrackerManager.bountyLevel(player.getUUID())
      );
      return 1;
   }

   private static int ffChallenges(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      Chat.raw(player, "§6§lDAILY CHALLENGES");
      for (com.fortuneandfavors.economy.DailyWeeklyChallengeManager.Challenge c : com.fortuneandfavors.economy.DailyWeeklyChallengeManager.daily()) {
         int p = com.fortuneandfavors.economy.DailyWeeklyChallengeManager.progressOf(player, c.id);
         Chat.raw(player, " §e" + c.name + "§7 - " + c.desc + " §8(" + Math.min(p, c.target) + "/" + c.target + ") §7reward §a$" + c.reward);
      }
      Chat.raw(player, "§6§lWEEKLY CHALLENGES");
      for (com.fortuneandfavors.economy.DailyWeeklyChallengeManager.Challenge c : com.fortuneandfavors.economy.DailyWeeklyChallengeManager.weekly()) {
         int p = com.fortuneandfavors.economy.DailyWeeklyChallengeManager.progressOf(player, c.id);
         Chat.raw(player, " §e" + c.name + "§7 - " + c.desc + " §8(" + Math.min(p, c.target) + "/" + c.target + ") §7reward §a$" + c.reward);
      }
      return 1;
   }

   private static int ffCodex(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      Chat.raw(player, "§6§lBOSS CODEX");
      java.util.Map<String, com.fortuneandfavors.economy.BossCodexManager.BossEntry> codex = com.fortuneandfavors.economy.BossCodexManager.all();
      if (codex.isEmpty()) {
         Chat.raw(player, " §7No bosses fought yet. Summon one with a raid boss token!");
      }
      for (java.util.Map.Entry<String, com.fortuneandfavors.economy.BossCodexManager.BossEntry> e : codex.entrySet()) {
         com.fortuneandfavors.economy.BossCodexManager.BossEntry b = e.getValue();
         Chat.raw(
            player,
            " §d"
               + com.fortuneandfavors.economy.BossCodexManager.displayName(e.getKey())
               + "§7 - kills §f"
               + b.kills
               + "§7 · deaths caused §f"
               + b.deathsCaused
               + "§7 · fastest §f"
               + (b.fastestBy.isEmpty() ? "-" : b.fastestBy + " (" + b.fastestMs / 1000L + "s)")
         );
      }
      return 1;
   }

   private static int ffRecords(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      Chat.raw(player, "§6§lFIRST-EVER RECORDS");
      java.util.Map<String, String> records = com.fortuneandfavors.economy.FirstEverRecordManager.all();
      if (records.isEmpty()) {
         Chat.raw(player, " §7No records yet - go make history!");
      }
      for (java.util.Map.Entry<String, String> e : records.entrySet()) {
         Chat.raw(player, " §5★ §f" + com.fortuneandfavors.economy.FirstEverRecordManager.displayForNews(e.getKey()) + "§7 - " + e.getValue());
      }
      return 1;
   }

   private static int guildMenu(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      GuildMenu.open(player);
      return 1;
   }

   private static int guildCreate(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = GuildManager.create(player, name);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      } else {
         GuildManager.save(((CommandSourceStack)ctx.getSource()).getServer());
         return 1;
      }
   }

   private static int guildJoin(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      Guild g = GuildManager.byName(name);
      if (g == null) {
         Chat.msg(player, "&cNo guild named &f" + name + "&c exists.");
         return 0;
      } else {
         String err = GuildManager.join(player, g.id);
         if (err != null) {
            Chat.msg(player, "&c" + err);
            return 0;
         } else {
            GuildManager.save(((CommandSourceStack)ctx.getSource()).getServer());
            return 1;
         }
      }
   }

   private static int guildInvite(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = GuildManager.invite(player, target);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      } else {
         GuildManager.save(((CommandSourceStack)ctx.getSource()).getServer());
         return 1;
      }
   }

   private static int guildLeave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = GuildManager.leave(player);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      } else {
         // Nothing actually changed - this only armed the confirmation - but the
         // save is harmless and keeps one exit path for the whole leave flow.
         GuildManager.save(((CommandSourceStack)ctx.getSource()).getServer());
         return 1;
      }
   }

   private static int guildLeaveConfirm(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = GuildManager.leaveConfirm(player);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      } else {
         GuildManager.save(((CommandSourceStack)ctx.getSource()).getServer());
         return 1;
      }
   }

   private static int guildLeaveCancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      GuildManager.leaveCancel(player);
      return 1;
   }

   private static int guildDisband(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = GuildManager.disband(player);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      } else {
         GuildManager.save(((CommandSourceStack)ctx.getSource()).getServer());
         return 1;
      }
   }

   private static int guildPromote(CommandContext<CommandSourceStack> ctx, String member) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = GuildManager.promote(player, member);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      }

      return 1;
   }

   private static int guildDemote(CommandContext<CommandSourceStack> ctx, String member) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = GuildManager.demote(player, member);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      }

      return 1;
   }

   private static int openGuildMailGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (GuildManager.getGuild(player.getUUID()) == null) {
         Chat.msg(player, "&cYou're not in a guild.");
         return 0;
      }

      GuildMailMenu.open(player);
      return 1;
   }

   /** /guild mail send <member> [subject] - attaches the item in the sender's main hand. */
   private static int guildMailSend(CommandContext<CommandSourceStack> ctx, String member, String subject) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      ItemStack held = player.getMainHandItem();
      if (held.isEmpty()) {
         Chat.msg(player, "&cHold the item you want to attach, then run the command again.");
         return 0;
      }

      String err = GuildManager.sendMail(player, member, subject, held.copy(), null);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      }

      held.shrink(1);
      return 1;
   }

   private static int guildSkills(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      GuildSkillsMenu.open(player);
      return 1;
   }

   private static int guildMotd(CommandContext<CommandSourceStack> ctx, String text) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = GuildManager.setMotd(player, text);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      } else {
         GuildManager.save(((CommandSourceStack)ctx.getSource()).getServer());
         return 1;
      }
   }

   private static int guildSettings(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = GuildManager.toggleFriendlyFire(player);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      } else {
         GuildManager.save(((CommandSourceStack)ctx.getSource()).getServer());
         return 1;
      }
   }

   private static int guildInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      Guild g = GuildManager.getGuild(player.getUUID());
      if (g == null) {
         Chat.msg(player, "&7You're not in a guild. &e/guild&7 to see your invites or found one.");
         return 0;
      } else {
         GuildMenu.open(player);
         return 1;
      }
   }

   private static int guildInfo(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      Guild g = GuildManager.getGuild(target.getUUID());
      if (g == null) {
         Chat.msg(player, "&c" + target.getName().getString() + " isn't in a guild.");
         return 0;
      } else {
         Chat.raw(
            player,
            "&6&l"
               + g.name
               + "&r &7- &e"
               + g.members.size()
               + " member"
               + (g.members.size() == 1 ? "" : "s")
               + " &7· &cPvP "
               + g.pvp
               + "&7 · &aWealth "
               + Chat.moneyStr(g.wealth)
               + "&7 · &bPvE "
               + g.pve
         );
         GuildMenu.openView(player, g);
         return 1;
      }
   }

   private static int mapEditList(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         Chat.raw(p, "&6&lMap Edit&r&7 - you can edit these maps:");
         Chat.raw(p, "  &e/mapedit 1.8arena&7, &e/modernarena&7, &e/other&7, &e/luckypvp&7 - &fone shared duel arena map");
         Chat.raw(p, "  &e/mapedit skywars&7 - &fSky Wars map");
         Chat.raw(p, "  &e/mapedit bedwars&7 - &fBed Wars map");
         Chat.raw(p, "  &7You start from the CURRENT map. &e/mapedit <style> restore&7 brings back the built-in arena.");
         return 1;
      }
   }

   private static int mapEdit(CommandContext<CommandSourceStack> ctx, String mode) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String err = MapEditor.start(p.level().getServer(), p, mode);
         if (err != null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
            return 0;
         } else {
            return 1;
         }
      }
   }

   private static int mapRestore(CommandContext<CommandSourceStack> ctx, String mode) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String err = MapEditor.restore(p, mode);
         if (err != null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
            return 0;
         } else {
            return 1;
         }
      }
   }

   private static int mapExit(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String err = MapEditor.exit(p);
         if (err != null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
            return 0;
         } else {
            return 1;
         }
      }
   }

   private static int mapExport(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String err = MapEditor.export(p);
         if (err != null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
            return 0;
         } else {
            return 1;
         }
      }
   }

   private static boolean featureOn(ServerPlayer player, String feature) {
      if (ModConfig.is(feature)) {
         return true;
      }

      Chat.msg(player, "&c" + ModConfig.displayName(feature) + " is disabled on this server.");
      return false;
   }

   private static int openShop(CommandContext<CommandSourceStack> ctx, Category category) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "shop")) {
         return 0;
      }

      ShopMenu.open(player, category, 0);
      return 1;
   }

   private static int quickBuy(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "shop")) {
         return 0;
      } else {
         ItemInput input = ItemArgument.getItem(ctx, "item");
         Item item = (Item)input.item().value();
         if (ShopData.shopBanned(item)) {
            Chat.msg(player, "&cThe shop doesn't sell that.");
            return 0;
         } else {
            ShopEntry entry = ShopData.findEntry(item);
            if (entry == null) {
               Chat.msg(player, "&cThat item is not sold in the shop.");
               return 0;
            } else {
               String denial = ShopProgression.canBuy(player.getUUID(), item);
               if (denial != null) {
                  Chat.msg(player, denial);
                  return 0;
               } else {
                  long price = ShopData.purchaseTotal(player.getUUID(), entry, amount);
                  if (!EconomyManager.takeCash(player.getUUID(), price)) {
                     Chat.msg(player, "&cNot enough money! Need " + Chat.moneyStr(price));
                     return 0;
                  } else {
                     ItemStack stack = input.createItemStack(amount);
                     int given = InventoryHelper.giveOrDrop(player, stack);
                     SoundUtil.play(player, ModSounds.BUY);
                     ChatCoalescer.buyMessage(player, new ItemStack(item), given, price);
                     return given;
                  }
               }
            }
         }
      }
   }

   private static int prisonOpen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = PrisonManager.enter(player);
      if (err != null) {
         Chat.msg(player, "&c" + err);
      }
      return 1;
   }

   private static int prisonLeave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      PrisonManager.leave(player);
      return 1;
   }

   private static int prisonRankUp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!PrisonManager.isInPrison(player)) {
         Chat.msg(player, "&cYou're not in prison - use &f/prison&c to enter first.");
         return 0;
      }
      String err = PrisonManager.rankUp(player);
      if (err != null) {
         Chat.msg(player, "&c" + err);
      }
      return 1;
   }

   private static int prisonSell(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!PrisonManager.isInPrison(player)) {
         Chat.msg(player, "&cYou're not in prison - use &f/prison&c to enter first.");
         return 0;
      }
      String err = PrisonManager.sellAll(player);
      if (err != null) {
         Chat.msg(player, "&c" + err);
      }
      return 1;
   }

   private static int sellHand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (PrisonManager.isInPrison(player)) {
         String err = PrisonManager.sellHand(player);
         if (err != null) {
            Chat.msg(player, "&c" + err);
         }
         return 1;
      }
      if (!featureOn(player, "shop")) {
         return 0;
      } else {
         ItemStack stack = player.getMainHandItem();
         long value = BlockValues.valueOf(stack);
         if (!stack.isEmpty() && value > 0L) {
            value = Math.round((float)value * SkillManager.sellMultiplier(player.getUUID()));
            String name = stack.getHoverName().getString();
            int count = stack.getCount();
            stack.setCount(0);
            EconomyManager.addCash(player.getUUID(), value);
            SoundUtil.play(player, ModSounds.SELL);
            Chat.raw(player, "§aSold §f" + name + "§a x" + count + " for " + Chat.moneyStr(value));
            return count;
         } else {
            Chat.msg(player, "&cThe item in your hand has no sell value.");
            return 0;
         }
      }
   }

   private static int sellAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (PrisonManager.isInPrison(player)) {
         String err = PrisonManager.sellAll(player);
         if (err != null) {
            Chat.msg(player, "&c" + err);
         }
         return 1;
      }
      if (!featureOn(player, "shop")) {
         return 0;
      }

      long total = 0L;
      int sold = 0;
      float sellMult = SkillManager.sellMultiplier(player.getUUID());

      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         ItemStack stack = player.getInventory().getItem(i);
         long value = BlockValues.valueOf(stack);
         if (value > 0L) {
            sold += stack.getCount();
            total += Math.round((float)value * sellMult);
            player.getInventory().setItem(i, ItemStack.EMPTY);
         }
      }

      if (total <= 0L) {
         Chat.msg(player, "&cYou do not have anything sellable.");
         return 0;
      } else {
         player.getInventory().setChanged();
         EconomyManager.addCash(player.getUUID(), total);
         SoundUtil.play(player, ModSounds.SELL);
         Chat.raw(player, "§aSold §f" + sold + "§a items for " + Chat.moneyStr(total));
         return sold;
      }
   }

   private static int balance(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "balance")) {
         return 0;
      }

      long gems = EconomyManager.gemBalance(player.getUUID());
      Chat.raw(
         player,
         "§8[§2Fortune & Favors§8]§7 Balance: "
            + Chat.moneyStr(EconomyManager.balance(player.getUUID()))
            + (gems > 0 ? "§7 · §5Gems: §f" + gems : "")
            + "§7 Pending items: §f"
            + EconomyManager.collectionCount(player.getUUID())
      );
      return 1;
   }

   private static int balanceOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "balance")) {
         return 0;
      }

      ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
      Chat.raw(
         player, "§8[§2Fortune & Favors§8]§7 " + target.getName().getString() + "§7's balance: " + Chat.moneyStr(EconomyManager.balance(target.getUUID()))
      );
      return 1;
   }

   private static int collect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      int claimed = EconomyManager.claimItems(player);
      if (claimed <= 0) {
         Chat.msg(player, "&7No pending items to collect (auction winnings, chest-shop payouts and boss loot boxes all land here).");
         return 0;
      } else {
         Chat.raw(player, "§aCollected §f" + claimed + "§a pending items.");
         return claimed;
      }
   }

   private static int openSellGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (PrisonManager.isInPrison(player)) {
         com.fortuneandfavors.menu.PrisonMenu.open(player);
         return 1;
      }
      if (!featureOn(player, "shop")) {
         return 0;
      }

      SellMenu.open(player);
      return 1;
   }

   private static int openAuctionGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "auction")) {
         return 0;
      }

      AuctionMenu.open(player);
      return 1;
   }

   private static int openClaimGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "claims")) {
         return 0;
      }

      com.fortuneandfavors.menu.ClaimMenu.open(player);
      return 1;
   }

   /** One screen that says exactly why claims are (or aren't) working: the
    *  feature flag, how many claims loaded, whether writes are enabled, the real
    *  data file on disk, and the caller's own balance / slots / position. Written
    *  because "claims don't work" can mean four very different failures (feature
    *  off, file unreadable so writes are disabled, no money, or standing outside
    *  the chunk you're trying to claim) and previously all of them were silent. */
   private static int claimStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      UUID id = player.getUUID();
      boolean enabled = com.fortuneandfavors.economy.ModConfig.is("claims");
      boolean writable = ClaimManager.writable();
      java.nio.file.Path file = ClaimManager.dataFile();

      Chat.raw(player, "&6&lClaim system status");
      Chat.raw(
         player,
         "&7Feature flag: "
            + (enabled ? "&aenabled" : "&cDISABLED &7- set \"claims\": true in the server config.json (or /ff config)")
      );
      Chat.raw(player, "&7Claims loaded: &f" + ClaimManager.claimCount());
      Chat.raw(
         player,
         "&7Writes: " + (writable ? "&aenabled" : "&cDISABLED &7- nothing can be saved; run &f/claim recover&7 or fix the file")
      );

      if (file == null) {
         Chat.raw(player, "&7Data file: &8not resolved yet (has the world finished loading?)");
      } else {
         String detail = "&8(not written yet)";

         try {
            if (java.nio.file.Files.exists(file)) {
               detail = "&8" + java.nio.file.Files.size(file) + " bytes, " + java.nio.file.Files.getLastModifiedTime(file);
            }
         } catch (Exception var12) {
         }

         Chat.raw(player, "&7Data file: &f" + file.getFileName() + " " + detail);
         Chat.raw(player, "&8  " + file);
      }

      Chat.raw(
         player,
         "&7Your claims: &f"
            + ClaimManager.usedClaims(id)
            + "&7/&f"
            + ClaimManager.maxClaims(id)
            + "&7 - next slot "
            + Chat.moneyStr(ClaimManager.slotUpgradeCost(id))
      );
      Chat.raw(
         player,
         "&7Your balance: &f"
            + Chat.moneyStr(EconomyManager.balance(id))
            + "&7 - a chunk costs "
            + Chat.moneyStr(ClaimManager.CHUNK_CLAIMER_PRICE)
      );
      var here = ClaimManager.claimAt(player);
      if (here == null) {
         Chat.raw(player, "&7Standing in: &8unclaimed land");
      } else {
         Chat.raw(
            player,
            "&7Standing in: "
               + (here.owner.equals(id) ? "&ayour claim" : "&e" + here.ownerName + "'s claim")
               + " &7at &f"
               + here.minX
               + ", "
               + here.minZ
               + " &7(chunk &f"
               + Math.floorDiv(here.minX, 16)
               + ", "
               + Math.floorDiv(here.minZ, 16)
               + "&7)"
         );
      }

      if (ClaimManager.hasPendingAbandon(id)) {
         Chat.raw(player, "&eAn abandon is waiting for confirmation - type &f/claim abandon confirm&e.");
      }

      if (!enabled || !writable) {
         Chat.raw(
            player,
            "&cClaims are not fully working: &7" + (!enabled ? "the feature flag is off." : "the save file could not be written.")
         );
      } else {
         Chat.raw(player, "&aClaims look healthy. &7Get a claimer with &f/claim give&7, then right-click the ground.");
      }

      return 1;
   }

   private static int claimGive(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "claims")) {
         return 0;
      }

      boolean gave = false;
      if (!ModItems.hasChunkClaimer(player)) {
         InventoryHelper.giveOrDrop(player, ModItems.chunkClaimer());
         gave = true;
      }

      if (!gave) {
         Chat.msg(player, "&7You already have a Chunk Claimer. Sneak-right-click inside your claim to open its menu.");
         return 0;
      } else {
         Chat.raw(player, "&aYou received a &fChunk Claimer&a!");
         Chat.msg(player, "&7Right-click once to claim that whole chunk (" + Chat.moneyStr(2560L) + "/chunk).");
         Chat.msg(player, "&7Sneak-right-click a chest in your claim to open its permission menu. /claim abandon to remove.");
         return 1;
      }
   }

   private static int claimAbandon(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "claims")) {
         return 0;
      } else {
         return ClaimManager.abandon(player) ? 1 : 0;
      }
   }

   private static int claimAbandonConfirm(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "claims")) {
         return 0;
      } else {
         return ClaimManager.abandonConfirm(player) ? 1 : 0;
      }
   }

   private static int claimSlotsInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "claims")) {
         return 0;
      }

      UUID id = player.getUUID();
      Chat.raw(player, "&7Claim slots: &f" + ClaimManager.usedClaims(id) + "&7 used / &f" + ClaimManager.maxClaims(id) + "&7 max.");
      Chat.msg(player, "&7Buy another slot (pricier each time): &f/claim slots buy&7 - next costs " + Chat.moneyStr(ClaimManager.slotUpgradeCost(id)) + ".");
      return 1;
   }

   private static int claimSlotsBuy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "claims")) {
         return 0;
      } else {
         return ClaimManager.buySlot(player) ? 1 : 0;
      }
   }

   /** The outcome of one recovery run, shared by the GUI and the commands so both
    *  can report the same numbers. */
   public static final class RecoveryResult {
      public final int recoverable;
      public final int merged;
      public final int total;
      public final boolean saved;
      public final String noun;

      private RecoveryResult(int recoverable, int merged, int total, boolean saved, String noun) {
         this.recoverable = recoverable;
         this.merged = merged;
         this.total = total;
         this.saved = saved;
         this.noun = noun;
      }
   }

   /** Merges every record of the given kind from the save file and every backup
    *  generation, then persists the merged table. This is the ONE place recovery
    *  happens: {@link #performRecovery} (the GUI) and every command call it, so a
    *  preview on screen can never disagree with what actually comes back. It
    *  never deletes a live record. */
   public static RecoveryResult runRecovery(MinecraftServer server, com.fortuneandfavors.menu.RecoveryMenu.Kind kind) {
      if (server == null) {
         return null;
      }

      int recoverable;
      int merged;
      boolean saved;
      String noun;
      switch (kind) {
         case CLAIMS:
            recoverable = ClaimManager.countRecoverable();
            merged = ClaimManager.recoverFromBackups();
            saved = merged > 0 && ClaimManager.save(server);
            noun = "claim";
            break;
         case SPAWNERS:
            recoverable = com.fortuneandfavors.economy.SpawnerManager.countRecoverable();
            merged = com.fortuneandfavors.economy.SpawnerManager.recoverFromBackups(server);
            saved = merged > 0 && com.fortuneandfavors.economy.SpawnerManager.save(server);
            noun = "spawner";
            break;
         case MACHINES:
            recoverable = com.fortuneandfavors.economy.MachineManager.countRecoverable();
            merged = com.fortuneandfavors.economy.MachineManager.recoverFromBackups(server);
            saved = merged > 0 && com.fortuneandfavors.economy.MachineManager.save(server);
            noun = "machine";
            break;
         default:
            return null;
      }

      int total = switch (kind) {
         case CLAIMS -> ClaimManager.claimCount();
         case SPAWNERS -> com.fortuneandfavors.economy.SpawnerManager.count();
         case MACHINES -> com.fortuneandfavors.economy.MachineManager.count();
      };

      return new RecoveryResult(recoverable, merged, total, saved, noun);
   }

   /** The GUI's entry point: runs the exact same recovery as the commands and
    *  tells the player the outcome in chat. */
   public static void performRecovery(ServerPlayer viewer, com.fortuneandfavors.menu.RecoveryMenu.Kind kind) {
      RecoveryResult r = runRecovery(viewer.level().getServer(), kind);
      if (r == null) {
         return;
      }

      if (r.merged <= 0) {
         Chat.msg(viewer, "&7Nothing to recover - every " + r.noun + " in the file and its backups is already loaded. &8(" + r.total + " active)");
         return;
      }

      Chat.msg(
         viewer,
         r.saved
            ? "&aRecovered &f" + r.merged + "&a of &f" + r.recoverable + "&a recoverable " + r.noun + "(s). &f" + r.total + "&a " + r.noun + "(s) active and saved."
            : "&cRecovered &f" + r.merged + "&c " + r.noun + "(s) into memory, but they could NOT be saved - writes are disabled. Fix the file, then run this again."
      );
   }

   private static void reportRecovery(CommandSourceStack source, RecoveryResult r) {
      if (r == null) {
         return;
      }

      if (r.merged <= 0) {
         restoreLine(
            source,
            "&7Nothing to recover - every " + r.noun + " in the file and its backups is already loaded. &8(" + r.total + " active)"
         );
      } else {
         restoreLine(
            source,
            r.saved
               ? "&aRecovered &f" + r.merged + "&a of &f" + r.recoverable + "&a recoverable " + r.noun + "(s). &f" + r.total + "&a " + r.noun + "(s) active and saved."
               : "&cRecovered &f" + r.merged + "&c " + r.noun + "(s) into memory, but they could NOT be saved - writes are disabled. Fix the file, then run this again."
         );
      }
   }

   private static int claimRecover(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      ServerPlayer player = source.getPlayer();
      if (player != null && !featureOn(player, "claims")) {
         return 0;
      }

      // In-game this opens the same preview-and-confirm screen as /ff restore
      // claims, so recovery is never a blind one-shot. The console keeps the
      // instant text path. /claim recover force still runs it immediately.
      if (player != null) {
         com.fortuneandfavors.menu.RecoveryMenu.open(player, com.fortuneandfavors.menu.RecoveryMenu.Kind.CLAIMS);
         return 1;
      }

      reportRecovery(source, runRecovery(source.getServer(), com.fortuneandfavors.menu.RecoveryMenu.Kind.CLAIMS));
      return 1;
   }

   /** /claim recover force - the old instant behaviour, for scripts and console. */
   private static int claimRecoverForce(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      ServerPlayer player = source.getPlayer();
      if (player != null && !featureOn(player, "claims")) {
         return 0;
      }

      reportRecovery(source, runRecovery(source.getServer(), com.fortuneandfavors.menu.RecoveryMenu.Kind.CLAIMS));
      return 1;
   }

   /** One colored line to whoever ran the command (player or console). */
   private static void restoreLine(CommandSourceStack source, String colored) {
      source.sendSuccess(() -> Component.literal(Chat.colorize(colored)), false);
   }

   /** Diagnostic for "why does this item show the wrong tier?". Prints the raw
    *  {@code ff_tier} tag next to the tier the mod derives from it, plus the
    *  name and the descriptive lore lines, so a genuine tag mismatch can be told
    *  apart from a name that was styled once and never re-rendered, or from lore
    *  that merely describes what higher tiers do. */
   private static int ffTierDiagnostic(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      ItemStack stack = player.getMainHandItem();
      if (stack.isEmpty()) {
         Chat.msg(player, "&cHold the item you want to inspect in your main hand.");
         return 0;
      }

      net.minecraft.world.item.component.CustomData data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
      String raw = data == null
         ? "(no custom_data)"
         : data.copyTag().getInt("ff_tier").map(String::valueOf).orElse("(absent)");
      String name = stack.getHoverName().getString();
      int tier = ModItems.tierOf(stack);

      Chat.raw(player, "&6&lTier diagnostic");
      Chat.raw(player, "&7Item: &f" + name);
      Chat.raw(player, "&7Type: &f" + ModItems.typeOf(stack));
      Chat.raw(player, "&7Raw &fff_tier&7 tag: &f" + raw);
      Chat.raw(player, "&7Tier the mod computes: &f" + tier + "&7/" + ModItems.MAX_TIER);
      Chat.raw(player, "&7Forge legendary: &f" + ModItems.isForgeLegendary(stack));
      Chat.raw(player, "&7Name carries a tier suffix: &f" + name.contains("[Tier"));

      List<Component> lore = new java.util.ArrayList<>();
      net.minecraft.world.item.component.ItemLore existing = stack.get(
         net.minecraft.core.component.DataComponents.LORE
      );
      if (existing != null) {
         for (Component line : existing.lines()) {
            String s = line.getString();
            if (s.startsWith("§8Current Tier ") || s.startsWith("§8Upgrade at Tier") || s.contains("Tier III") || s.contains("Tier II")) {
               lore.add(line);
            }
         }
      }

      if (lore.isEmpty()) {
         Chat.raw(player, "&7Lore tier mentions: &fnone");
      } else {
         Chat.raw(player, "&7Lore tier mentions: &8(" + lore.size() + ")");
         Chat.raw(player, "&8  §fCurrent Tier …§8 = this item's real tier. §fUpgrade at Tier …§8 = what a higher tier would add.");
         for (Component line : lore) {
            Chat.raw(player, "&8  " + line.getString());
         }
      }

      return 1;
   }

   /** Dry run for both /ff restore claims and /claim restore. Reports exactly what
    *  recovery would bring back - and from where - without touching a single
    *  claim, so an admin can decide before merging a stale backup into live land.
    *  Every save file and backup generation is counted, and a claim already
    *  loaded is never counted twice. */
   private static int ffRestoreClaimsPreview(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      ServerPlayer player = source.getPlayer();
      if (player != null && !featureOn(player, "claims")) {
         return 0;
      }

      // In-game, the preview IS the GUI: every recoverable claim is listed on a
      // slot and nothing is merged until Confirm. The console keeps the text
      // preview, which is the only thing a script can read anyway.
      if (player != null) {
         com.fortuneandfavors.menu.RecoveryMenu.open(player, com.fortuneandfavors.menu.RecoveryMenu.Kind.CLAIMS);
         return 1;
      }

      int recoverable = ClaimManager.countRecoverable();
      int loaded = ClaimManager.claimCount();
      boolean writable = ClaimManager.writable();
      java.nio.file.Path file = ClaimManager.dataFile();

      restoreLine(source, "&6&lClaim recovery &7- preview");
      restoreLine(source, "&7Claims loaded right now: &f" + loaded);
      restoreLine(
         source,
         recoverable > 0
            ? "&aRecoverable from the file + backups: &f" + recoverable + "&a claim(s)"
            : "&7Recoverable from the file + backups: &fnone"
      );
      restoreLine(
         source,
         "&7Writes: "
            + (writable
               ? "&aenabled"
               : "&cDISABLED &7- recovery cannot be persisted until the file is fixed (see /claim status)")
      );

      if (file == null) {
         restoreLine(source, "&7Data file: &8not resolved yet - has the world finished loading?");
      } else {
         java.util.List<java.nio.file.Path> scanned = new java.util.ArrayList<>();
         scanned.add(file);
         scanned.addAll(com.fortuneandfavors.util.JsonUtil.backups(file));
         int present = 0;

         for (java.nio.file.Path p : scanned) {
            try {
               if (p != null && java.nio.file.Files.exists(p)) {
                  present++;
               }
            } catch (Exception ignored) {
            }
         }

         restoreLine(source, "&7Scanned &f" + present + "&7 of &f" + scanned.size() + "&7 file(s) in &8" + file.getParent());
         restoreLine(source, "&8  " + file);
      }

      if (recoverable > 0) {
         restoreLine(source, "&eRun &f/ff restore claims confirm &e(or &f/claim restore confirm&e) to merge them in.");
      } else {
         restoreLine(source, "&7Nothing to merge - the file and every backup hold either no claims or only ones already loaded.");
      }

      return 1;
   }

   /** Applies claim recovery. Merges every claim found in the save file and each
    *  backup generation, then persists the merged table. It never deletes what is
    *  already live, and it merges into memory before saving, so a failed or
    *  partial recovery leaves the data on disk exactly as it was. */
   private static int ffRestoreClaims(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      ServerPlayer player = source.getPlayer();
      if (player != null && !featureOn(player, "claims")) {
         return 0;
      }

      reportRecovery(source, runRecovery(source.getServer(), com.fortuneandfavors.menu.RecoveryMenu.Kind.CLAIMS));
      return 1;
   }

   /** One recovery subcommand for a kind that has no /claim alias of its own:
    *  in-game it opens the preview-and-confirm GUI, console runs it instantly. */
   private static int restoreKind(CommandContext<CommandSourceStack> ctx, com.fortuneandfavors.menu.RecoveryMenu.Kind kind)
      throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      ServerPlayer player = source.getPlayer();
      if (player != null) {
         com.fortuneandfavors.menu.RecoveryMenu.open(player, kind);
         return 1;
      }

      reportRecovery(source, runRecovery(source.getServer(), kind));
      return 1;
   }

   private static int ffRestoreSpawners(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      return restoreKind(ctx, com.fortuneandfavors.menu.RecoveryMenu.Kind.SPAWNERS);
   }

   private static int ffRestoreMachines(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      return restoreKind(ctx, com.fortuneandfavors.menu.RecoveryMenu.Kind.MACHINES);
   }

   private static int ffRestoreSpawnersForce(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      reportRecovery((CommandSourceStack)ctx.getSource(), runRecovery(((CommandSourceStack)ctx.getSource()).getServer(), com.fortuneandfavors.menu.RecoveryMenu.Kind.SPAWNERS));
      return 1;
   }

   private static int ffRestoreMachinesForce(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      reportRecovery((CommandSourceStack)ctx.getSource(), runRecovery(((CommandSourceStack)ctx.getSource()).getServer(), com.fortuneandfavors.menu.RecoveryMenu.Kind.MACHINES));
      return 1;
   }

   private static int spawnerRecover(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      ServerPlayer player = source.getPlayer();
      if (player != null && !featureOn(player, "claims")) {
         return 0;
      }

      if (player != null) {
         com.fortuneandfavors.menu.RecoveryMenu.open(player, com.fortuneandfavors.menu.RecoveryMenu.Kind.SPAWNERS);
         return 1;
      }

      reportRecovery(source, runRecovery(source.getServer(), com.fortuneandfavors.menu.RecoveryMenu.Kind.SPAWNERS));
      return 1;
   }

   /** /spawners recover force - the instant text path for scripts and console. */
   private static int spawnerRecoverForce(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      reportRecovery((CommandSourceStack)ctx.getSource(), runRecovery(((CommandSourceStack)ctx.getSource()).getServer(), com.fortuneandfavors.menu.RecoveryMenu.Kind.SPAWNERS));
      return 1;
   }

   private static int spawnersSellAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      Inventory inv = player.getInventory();
      long total = 0L;
      int count = 0;

      // 1) Spawner loot carried in the player's inventory.
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack stack = inv.getItem(i);
         if (!stack.isEmpty() && ModItems.isSpawnerLoot(stack)) {
            long v = BlockValues.valueOf(stack);
            if (v > 0L) {
               total += v;
               count += stack.getCount();
               inv.setItem(i, ItemStack.EMPTY);
            }
         }
      }

      // 2) Buffered loot in every ITEMS-mode spawner the player placed. Runs entirely
      // in memory over the spawner registry - no chunk loads, so it never lags.
      long[] owned = SpawnerManager.sellAllOwned(player);
      total += owned[0];
      count += (int)owned[1];
      long spawnerCount = owned[2];

      if (count == 0) {
         Chat.msg(player, "&7No spawner loot in your inventory or owned spawners to sell.");
         return 0;
      }

      EconomyManager.addCash(player.getUUID(), total);
      if (spawnerCount > 0L && count > (int)owned[1]) {
         Chat.raw(player, "&aSold &f" + count + "&a spawner loot item(s) for " + Chat.moneyStr(total) + "&a (&f" + (count - (int)owned[1]) + "&a from inventory, &f" + spawnerCount + "&a owned spawner(s) emptied - level multiplier included).");
      } else if (spawnerCount > 0L) {
         Chat.raw(player, "&aSold all loot from &f" + spawnerCount + "&a of your spawners for " + Chat.moneyStr(total) + "&a (&f" + count + "&a items - level multiplier included).");
      } else {
         Chat.raw(player, "&aSold &f" + count + "&a spawner loot item(s) for " + Chat.moneyStr(total) + "&a (level multiplier included).");
      }
      return 1;
   }

   private static int tradeRequest(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "trade")) {
         return 0;
      } else {
         return TradeManager.requestTrade(player, target) ? 1 : 0;
      }
   }

   private static int tradeAccept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "trade")) {
         return 0;
      } else {
         return TradeManager.acceptRequest(player) ? 1 : 0;
      }
   }

   private static int lotteryMenu(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      LotteryMenu.open(player);
      return 1;
   }

   private static int lotteryBuy(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      LotteryManager.buy(player, amount);
      return 1;
   }

   private static int wormholeAnswer(CommandContext<CommandSourceStack> ctx, boolean accept) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "exclusive")) {
         return 0;
      } else {
         return accept ? (WormholeManager.accept(player) ? 1 : 0) : (WormholeManager.deny(player) ? 1 : 0);
      }
   }

   private static int openJobsGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "jobs")) {
         return 0;
      }

      JobMenu.open(player);
      return 1;
   }

   private static int openSkillsGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "skills")) {
         return 0;
      }

      SkillMenu.open(player);
      return 1;
   }

   private static int openMenuHub(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      MenuHubMenu.open(player);
      return 1;
   }

   // ------------------------------------------------- /stats scoreboard & /ff scoreboard

   /**
    * The scoreboard tree, built once for both roots.
    *
    * <p>{@code /ff scoreboard} and {@code /stats scoreboard} are the same board: hanging one
    * builder off two roots is what keeps a subcommand added here from being added to only one of
    * the spellings. Nothing in the tree carries a {@code requires} - a board is a thing you look
    * at, so every switch on it belongs to the player looking, which is also why it is reachable
    * from {@code /ff} at all rather than only from a command nobody thinks to type.
    *
    * <p>Bare {@code /ff scoreboard} opens the board's own screen rather than switching it on in
    * chat: the board is per player and every part of it is a choice, so the first thing a player
    * asking for it should get is the thing that shows them what they have and lets them change
    * it - and the screen turns the board on as it opens, so nothing has to be done twice.
    *
    * <p>Everything on that screen is also settable by typing, for the player who would rather:
    * the texts ({@code title}, {@code ip}, {@code custom}), which of the two board texts is the
    * banner ({@code banner}), the
    * line order ({@code move}) and each line's own switch ({@code on} / {@code off} /
    * {@code toggle}), with {@code lines} to read them all back.
    */
   private static LiteralArgumentBuilder<CommandSourceStack> scoreboardCommand() {
      return Commands.literal("scoreboard")
         .executes(ModCommands::statsScoreboardEdit)
         .then(Commands.literal("enable").executes(ctx -> statsScoreboardSet(ctx, true)))
         .then(Commands.literal("disable").executes(ctx -> statsScoreboardSet(ctx, false)))
         .then(Commands.literal("edit").executes(ModCommands::statsScoreboardEdit))
         .then(Commands.literal("lines").executes(ModCommands::statsScoreboardLines))
         .then(
            Commands.literal("title")
               .then(Commands.argument("text", StringArgumentType.greedyString()).executes(ModCommands::statsScoreboardTitle))
         )
         .then(
            Commands.literal("ip")
               .then(Commands.argument("text", StringArgumentType.greedyString()).executes(ModCommands::statsScoreboardIp))
         )
         .then(
            // The third written text, the one that belongs to the player rather than the server.
            // An empty string is a legal value here (it blanks the line), which is why this is a
            // greedy argument with an executor rather than a bare literal.
            Commands.literal("custom")
               .executes(ModCommands::statsScoreboardCustom)
               .then(Commands.argument("text", StringArgumentType.greedyString()).executes(ModCommands::statsScoreboardCustom))
         )
         .then(
            Commands.literal("banner")
               .then(Commands.literal("title").executes(ctx -> statsScoreboardBanner(ctx, false)))
               .then(Commands.literal("ip").executes(ctx -> statsScoreboardBanner(ctx, true)))
         )
         .then(
            Commands.literal("move")
               .then(
                  Commands.literal("up")
                     .then(Commands.argument("line", StringArgumentType.word()).executes(ctx -> statsScoreboardMove(ctx, -1)))
               )
               .then(
                  Commands.literal("down")
                     .then(Commands.argument("line", StringArgumentType.word()).executes(ctx -> statsScoreboardMove(ctx, 1)))
               )
         )
         // `on`/`off` are the whole board when they stand alone, and one line when given a line
         // name. They used to require the line argument and nothing else, so the command a player
         // actually types - `/ff scoreboard off` - matched no executor at all and answered with a
         // usage error, which reads as "the toggle is broken". The board-wide switch is the same
         // one `enable`/`disable` call, so there is one implementation behind both spellings.
         .then(
            Commands.literal("on")
               .executes(ctx -> statsScoreboardSet(ctx, true))
               .then(Commands.argument("line", StringArgumentType.word()).executes(ctx -> statsScoreboardLine(ctx, "on")))
         )
         .then(
            Commands.literal("off")
               .executes(ctx -> statsScoreboardSet(ctx, false))
               .then(Commands.argument("line", StringArgumentType.word()).executes(ctx -> statsScoreboardLine(ctx, "off")))
         )
         .then(
            Commands.literal("toggle")
               .then(Commands.argument("line", StringArgumentType.word()).executes(ctx -> statsScoreboardLine(ctx, "toggle")))
         );
   }

   /**
    * Shows this player's scoreboard and leaves it on, in chat rather than on a screen.
    *
    * <p>The bare {@code /stats} path. "Show" and "enable" are the same act on purpose: a board a
    * player asked to see once and which then vanished on the next refresh would be a board nobody
    * trusts. The pair of verbs exists because the two things a player wants are "put it back" and
    * "take it away" - {@link #statsScoreboardSet} is the second. {@code /ff scoreboard} opens the
    * screen instead, which is the same board with every switch on it.
    */
   private static int statsScoreboardShow(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }
      if (!featureOn(p, com.fortuneandfavors.economy.ScoreboardManager.FEATURE)) {
         return 0;
      }
      com.fortuneandfavors.economy.ScoreboardManager.enable(p);
      Chat.msg(p, "&aScoreboard on. &7Set it up with &f/ff scoreboard edit&7, or &f/ff scoreboard lines&7 to see what is on it. &f/ff scoreboard disable&7 hides it.");
      return 1;
   }

   private static int statsScoreboardSet(CommandContext<CommandSourceStack> ctx, boolean on) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }
      if (on && !featureOn(p, com.fortuneandfavors.economy.ScoreboardManager.FEATURE)) {
         return 0;
      }
      if (on) {
         com.fortuneandfavors.economy.ScoreboardManager.enable(p);
         Chat.msg(p, "&aScoreboard shown.");
      } else {
         // Deliberately not gated on the feature flag: switching something off has to keep
         // working even when the server-side gate is what is drawing it in the first place.
         com.fortuneandfavors.economy.ScoreboardManager.disable(p);
         Chat.msg(p, "&7Scoreboard hidden. &f/ff scoreboard&7 brings it back.");
      }
      return 1;
   }

   private static int statsScoreboardEdit(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }
      if (!featureOn(p, com.fortuneandfavors.economy.ScoreboardManager.FEATURE)) {
         return 0;
      }
      // The screen turns the board on itself (see ScoreboardEditMenu.open): every switch in it
      // is about a board the player is supposed to be watching change as they click.
      Chat.msg(
         p,
         "&6Your board &7- &fonly you see it&7. &fLeft-click&7 a line to switch it, &fright-click&7 to pick it up for reordering, and click the &fTitle&7/&fAddress&7 tiles to retype them."
      );
      com.fortuneandfavors.menu.ScoreboardEditMenu.open(p);
      return 1;
   }

   /**
    * {@code /stats scoreboard banner title|ip} - which of the two texts sits on top.
    *
    * <p>The edit screen offers the same swap as a button; the command exists because it is one
    * unambiguous choice and a player who knows which they want should not have to open a screen
    * to make it.
    */
   private static int statsScoreboardBanner(CommandContext<CommandSourceStack> ctx, boolean addressOnTop) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }
      com.fortuneandfavors.economy.ScoreboardManager.Settings s = com.fortuneandfavors.economy.ScoreboardManager.settingsOf(p);
      com.fortuneandfavors.economy.ScoreboardManager.setBanner(s, addressOnTop);
      com.fortuneandfavors.economy.ScoreboardManager.show(p);
      Chat.msg(
         p,
         "&aBanner: &r" + com.fortuneandfavors.economy.ScoreboardManager.banner(s, p)
            + "&a. &7The other text is now the "
            + (addressOnTop ? "Title" : "Address")
            + " line."
      );
      if (addressOnTop && !com.fortuneandfavors.economy.ScoreboardManager.addressDrawable(p)) {
         Chat.msg(p, "&7Single player has no address to print - the title stays on top here.");
      }
      return 1;
   }

   /**
    * {@code /stats scoreboard move up|down <line>} - the reordering the edit screen does with a
    * picked-up line, as a command for anyone who would rather type it.
    */
   private static int statsScoreboardMove(CommandContext<CommandSourceStack> ctx, int direction) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }
      String key = StringArgumentType.getString(ctx, "line").toLowerCase(java.util.Locale.ROOT);
      if (!java.util.List.of(com.fortuneandfavors.economy.ScoreboardManager.LINE_KEYS).contains(key)) {
         Chat.msg(p, "&cUnknown line '&f" + key + "&c'. Try one of: &f" + String.join("&7, &f", com.fortuneandfavors.economy.ScoreboardManager.LINE_KEYS) + "&c.");
         return 0;
      }
      com.fortuneandfavors.economy.ScoreboardManager.Settings s = com.fortuneandfavors.economy.ScoreboardManager.settingsOf(p);
      if (!com.fortuneandfavors.economy.ScoreboardManager.moveLine(s, key, direction)) {
         Chat.msg(p, "&7" + com.fortuneandfavors.economy.ScoreboardManager.lineName(key) + " is already at the " + (direction < 0 ? "top" : "bottom") + ".");
         return 0;
      }
      com.fortuneandfavors.economy.ScoreboardManager.show(p);
      Chat.msg(p, "&aMoved &f" + com.fortuneandfavors.economy.ScoreboardManager.lineName(key) + "&a " + (direction < 0 ? "up" : "down") + ".");
      return 1;
   }

   /**
    * {@code /ff scoreboard on|off|toggle <line>} - one body line's switch, from chat.
    *
    * <p>The edit screen carries the same switches with icons on them; this is the same call for a
    * player who would rather type, and it is what makes the whole board settable without opening a
    * window - the two texts, the banner, the order and now the lines themselves.
    */
   private static int statsScoreboardLine(CommandContext<CommandSourceStack> ctx, String action) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }
      String key = StringArgumentType.getString(ctx, "line").toLowerCase(java.util.Locale.ROOT);
      if (!java.util.List.of(com.fortuneandfavors.economy.ScoreboardManager.LINE_KEYS).contains(key)) {
         Chat.msg(
            p,
            "&cUnknown line '&f" + key + "&c'. Try one of: &f"
               + String.join("&7, &f", com.fortuneandfavors.economy.ScoreboardManager.LINE_KEYS)
               + "&c."
         );
         return 0;
      }
      if ("on".equals(action) && !com.fortuneandfavors.economy.ScoreboardManager.lineAvailable(p, key)) {
         Chat.msg(p, "&7There is no address to print in single player - the Address line stays off here.");
         return 0;
      }
      com.fortuneandfavors.economy.ScoreboardManager.Settings s = com.fortuneandfavors.economy.ScoreboardManager.settingsOf(p);
      boolean on = switch (action) {
         case "on" -> true;
         case "off" -> false;
         default -> !com.fortuneandfavors.economy.ScoreboardManager.lineOn(s, key);
      };
      if (!com.fortuneandfavors.economy.ScoreboardManager.setLine(s, key, on)) {
         Chat.msg(
            p,
            "&7" + com.fortuneandfavors.economy.ScoreboardManager.lineName(key) + " is already " + (on ? "on" : "off")
               + ". &f/ff scoreboard lines&7 lists them all."
         );
         return 0;
      }
      // Painted here rather than left to the next refresh, because a switch you cannot watch land
      // is a switch you click twice.
      com.fortuneandfavors.economy.ScoreboardManager.show(p);
      Chat.msg(p, "&a" + com.fortuneandfavors.economy.ScoreboardManager.lineName(key) + (on ? " shown" : " hidden") + ".");
      return 1;
   }

   /**
    * {@code /ff scoreboard lines} - the board's lines, in order, with their switches.
    *
    * <p>The edit screen is the better way to change a board and the worse way to check one: this
    * answers "what is on my board, and in what order" without opening anything, and it names the
    * keys the {@code on} / {@code off} / {@code toggle} / {@code move} verbs take - which is the
    * question a player asks the first time one of them refuses them.
    */
   private static int statsScoreboardLines(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }
      com.fortuneandfavors.economy.ScoreboardManager.Settings s = com.fortuneandfavors.economy.ScoreboardManager.settingsOf(p);
      Chat.raw(p, "§6§lYour scoreboard " + (com.fortuneandfavors.economy.ScoreboardManager.isEnabled(p) ? "§a- on" : "§c- off"));
      int slot = 1;
      for (String key : com.fortuneandfavors.economy.ScoreboardManager.orderOf(s)) {
         boolean on = com.fortuneandfavors.economy.ScoreboardManager.lineOn(s, key);
         Chat.raw(
            p,
            " §8" + slot++ + ". §f" + key + " §7" + com.fortuneandfavors.economy.ScoreboardManager.lineName(key)
               + (on ? " §aON" : " §8OFF")
         );
      }
      Chat.raw(p, "§8/ff scoreboard on|off|toggle <line>, move up|down <line>, title <text>, ip <text>, custom <text>, banner title|ip");
      return 1;
   }

   private static int statsScoreboardTitle(CommandContext<CommandSourceStack> ctx) {
      return statsScoreboardText(ctx, true);
   }

   /**
    * {@code /ff scoreboard custom <text>} - writes the player's own line, or blanks it.
    *
    * <p>The one line on the board that is about nothing but its owner, so it is the one line with
    * no clamp to a non-empty value: {@code /ff scoreboard custom} with no text clears it, which is
    * the same thing the empty result in the rename prompt does. The line's own switch ({@code off
    * custom}) is still how you take it off the board without forgetting what it said.
    */
   private static int statsScoreboardCustom(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }
      if (!featureOn(p, com.fortuneandfavors.economy.ScoreboardManager.FEATURE)) {
         return 0;
      }
      String stored = com.fortuneandfavors.economy.ScoreboardManager.setCustomText(p, statsScoreboardTextOrNull(ctx));
      com.fortuneandfavors.economy.ScoreboardManager.show(p);
      Chat.msg(
         p,
         stored.isEmpty()
            ? "&7Your own line is now blank - it draws nothing. &f/ff scoreboard custom <text>&7 writes it again."
            : "&aYour own line: &r" + com.fortuneandfavors.economy.ScoreboardManager.colorize(stored) + "&a. &7Turn it with &f/ff scoreboard toggle custom&7."
      );
      return 1;
   }

   /** The {@code text} argument when it was supplied, or {@code null} when it was not. */
   private static String statsScoreboardTextOrNull(CommandContext<CommandSourceStack> ctx) {
      try {
         return StringArgumentType.getString(ctx, "text");
      } catch (IllegalArgumentException noArgument) {
         return null;
      }
   }

   private static int statsScoreboardIp(CommandContext<CommandSourceStack> ctx) {
      return statsScoreboardText(ctx, false);
   }

   /**
    * The two editable strings: the banner and the address on the IP line.
    *
    * <p>Both are clamped rather than validated, and clamped rather than rejected: a title is
    * decoration, and refusing to save somebody's twelve extra characters would be a rule with
    * nothing behind it. Colour codes are welcome in both, because every other string a player
    * can write in this mod takes them.
    */
   private static int statsScoreboardText(CommandContext<CommandSourceStack> ctx, boolean title) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }
      String text = StringArgumentType.getString(ctx, "text");
      if (text.length() > com.fortuneandfavors.economy.ScoreboardManager.MAX_TEXT) {
         text = text.substring(0, com.fortuneandfavors.economy.ScoreboardManager.MAX_TEXT);
      }
      // Through the manager rather than straight into the settings: one clamp, and writing an
      // address is also what switches its line on - the address is the one line a fresh board
      // leaves off, so a command that stored it and left the line off would be a command that
      // looked broken.
      String stored = com.fortuneandfavors.economy.ScoreboardManager.setText(p, !title, text);
      com.fortuneandfavors.economy.ScoreboardManager.show(p);
      Chat.msg(
         p,
         "&a" + (title ? "Title" : "Address") + " set to &r" + com.fortuneandfavors.economy.ScoreboardManager.colorize(stored) + "&a."
      );
      if (!title) {
         if (!com.fortuneandfavors.economy.ScoreboardManager.addressDrawable(p)) {
            Chat.msg(p, "&7Single player has no address to print, so the Address line stays off here.");
         } else if (stored.isEmpty()) {
            // The line reads itself off the handshake now, so an empty box is not an empty line.
            Chat.msg(p, "&7The Address line fills itself in - it shows the address you joined on.");
         } else {
            Chat.msg(p, "&7The Address line is on, showing &r" + com.fortuneandfavors.economy.ScoreboardManager.colorize(stored) + "&7.");
         }
      }
      return 1;
   }

   private static int bossSpawn(CommandContext<CommandSourceStack> ctx, String mob, double hp) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "boss")) {
         return 0;
      } else {
         String error = BossManager.spawn(player, mob, hp);
         if (error != null) {
            Chat.msg(player, "&c" + error);
            return 0;
         } else {
            return 1;
         }
      }
   }

   private static int bossClear(CommandContext<CommandSourceStack> ctx) {
      BossManager.clearBars(((CommandSourceStack)ctx.getSource()).getServer());
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(() -> Component.literal(Chat.colorize("&aBoss bars cleared. Any boss entities still alive stay in the world (untracked).")), true);
      return 1;
   }

   /**
    * /ff boss wipe - the "make it stop" button: every tracked boss fight is ended
    * now (the Time Lord's boss is discarded, his bar removed and - the part that
    * matters - any frozen tick rate released), and every remaining boss bar is
    * cleared. Unlike {@code /ff boss clear}, which only forgets the bars and
    * leaves the entities standing, this actually stops the fights.
    */
   private static int bossWipe(CommandContext<CommandSourceStack> ctx) {
      MinecraftServer server = ((CommandSourceStack)ctx.getSource()).getServer();
      int endedLords = com.fortuneandfavors.economy.TimeLordManager.abandonAll(server);
      boolean timeReleased = !com.fortuneandfavors.economy.TimeLordManager.isHoldingTime(server);
      com.fortuneandfavors.economy.WitherReworkManager.clearAll(server);
      BossManager.clearBars(server);
      String msg = "&aEnded &f" + endedLords + "&a Time Lord fight(s)"
         + (timeReleased ? " and released time." : " &c- but the tick rate is STILL frozen, tell an admin!")
         + " &7All boss bars cleared.";
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal(Chat.colorize(msg)), true);
      return 1;
   }

   private static int bossList(CommandContext<CommandSourceStack> ctx) {
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal(Chat.colorize("&7Active bosses: &f" + BossManager.list())), false);
      return 1;
   }

   private static int bountyPlace(CommandContext<CommandSourceStack> ctx, ServerPlayer target, long amount) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "bounty")) {
         return 0;
      } else {
         return BountyManager.place(player, target, amount) ? 1 : 0;
      }
   }

   private static int bountyCancel(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "bounty")) {
         return 0;
      } else {
         return BountyManager.cancel(player, target) ? 1 : 0;
      }
   }

   private static int bountyList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "bounty")) {
         return 0;
      }

      Map<UUID, Bounty> all = BountyManager.all();
      if (all.isEmpty()) {
         Chat.msg(player, "&7No bounties right now. Put one on a player with &f/bounty <player> <amount>&7.");
         return 0;
      }

      Chat.raw(player, "§8[§6Bounties§8]§7 Active:\n");

      for (Bounty e : all.values()) {
         Chat.raw(player, "§6" + e.targetName + "§7 - " + Chat.moneyStr(e.amount) + "§7 (set by §f" + e.posterName + "§7)");
      }

      return 1;
   }

   private static int openGemShop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      GemShopMenu.open(player);
      return 1;
   }

   private static int openTokenGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "token")) {
         return 0;
      }

      TokenMenu.open(player);
      return 1;
   }

   private static int tagShow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "tags")) {
         return 0;
      }

      Tag tag = TagManager.getTag(player.getUUID());
      if (tag == null) {
         Chat.msg(player, "&7You don't have a custom tag. Set one with &f/tag set <text>&7 and &f/tag color <r> <g> <b>&7.");
      } else {
         Chat.msg(player, "&7Your tag: &f" + tag.text() + "&7 (color " + tag.rgb() + ").");
      }

      return 1;
   }

   private static int tagSet(CommandContext<CommandSourceStack> ctx, String text) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "tags")) {
         return 0;
      }

      Tag prev = TagManager.getTag(player.getUUID());
      int rgb = prev != null ? prev.rgb() : 5636095;
      TagManager.setTag(player.getUUID(), text, rgb);
      TagManager.save(((CommandSourceStack)ctx.getSource()).getServer());
      TagManager.refreshTabList(((CommandSourceStack)ctx.getSource()).getServer());
      Chat.raw(player, "&aTag set: &f" + text + "&7. Pick a color with &f/tag color <r> <g> <b>&7.");
      return 1;
   }

   private static int tagColor(CommandContext<CommandSourceStack> ctx, int r, int g, int b) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "tags")) {
         return 0;
      } else {
         Tag prev = TagManager.getTag(player.getUUID());
         int rgb = r << 16 | g << 8 | b;
         if (prev == null) {
            Chat.msg(player, "&cSet a tag text first with &f/tag set <text>&c.");
            return 0;
         } else {
            TagManager.setTag(player.getUUID(), prev.text(), rgb);
            TagManager.save(((CommandSourceStack)ctx.getSource()).getServer());
            TagManager.refreshTabList(((CommandSourceStack)ctx.getSource()).getServer());
            Chat.raw(player, "&aTag color set to RGB(" + r + "," + g + "," + b + ").");
            return 1;
         }
      }
   }

   private static int tagClear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "tags")) {
         return 0;
      }

      TagManager.clearTag(player.getUUID());
      TagManager.save(((CommandSourceStack)ctx.getSource()).getServer());
      TagManager.refreshTabList(((CommandSourceStack)ctx.getSource()).getServer());
      Chat.msg(player, "&aTag cleared.");
      return 1;
   }

   private static int permsGive(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
      if (PermissionManager.grantEconomyAdmin(target.getUUID())) {
         PermissionManager.save(((CommandSourceStack)ctx.getSource()).getServer());
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(
               () -> Component.literal(Chat.colorize("&aGave &f" + target.getName().getString() + "&a the economy admin permission (/economy).")), true
            );
      } else {
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(() -> Component.literal(Chat.colorize("&7" + target.getName().getString() + " already has the economy admin permission.")), false);
      }

      return 1;
   }

   private static int permsRemove(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
      if (PermissionManager.revokeEconomyAdmin(target.getUUID())) {
         PermissionManager.save(((CommandSourceStack)ctx.getSource()).getServer());
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(() -> Component.literal(Chat.colorize("&aRemoved the economy admin permission from &f" + target.getName().getString())), true);
      } else {
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(() -> Component.literal(Chat.colorize("&7" + target.getName().getString() + " doesn't have the economy admin permission.")), false);
      }

      return 1;
   }

   private static int permsList(CommandContext<CommandSourceStack> ctx) {
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(() -> Component.literal(Chat.colorize("&7Economy admins: &f" + PermissionManager.economyAdmins().size())), false);

      for (UUID id : PermissionManager.economyAdmins()) {
         ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getServer().getPlayerList().getPlayer(id);
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(() -> Component.literal(Chat.colorize("  &7- &f" + (p != null ? p.getName().getString() : id.toString()))), false);
      }

      return 1;
   }

   private static int economyReset(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
      EconomyManager.setBalance(target.getUUID(), 0L);
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(() -> Component.literal(Chat.colorize("&aReset &f" + target.getName().getString() + "&a balance to $0.")), true);
      Chat.raw(target, "§eYour balance was reset by an admin.");
      return 1;
   }

   private static int economyTop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      List<Entry<UUID, Long>> sorted = new ArrayList<>(EconomyManager.allBalances().entrySet());
      sorted.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal(Chat.colorize("&6§lTop balances:")), false);
      int place = 1;

      for (Entry<UUID, Long> e : sorted) {
         if (place > 10) {
            break;
         }

         ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getServer().getPlayerList().getPlayer(e.getKey());
         String name = p != null ? p.getName().getString() : e.getKey().toString().substring(0, 8);
         int i = place;
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(() -> Component.literal(Chat.colorize("&f" + i + ". &7" + name + " &f" + Chat.moneyStr(e.getValue()))), false);
         place++;
      }

      return 1;
   }

   private static int economyHelp(CommandContext<CommandSourceStack> ctx) {
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal(Chat.colorize("&6§lFortune & Favors commands:")), false);

      for (String line : new String[]{
         "&7/shop, /buy <item> [amount], /sell, /balance, /balance transfer <p> <amt>",
         "&7/auction (or /au), /auction sell bid|fixed ..., /claim, /claim abandon [confirm], /claim recover (admin)",
         "&7/auction search <text> | /auction mine | /auction bids | /auction bid <id> <amt> | /auction buy <id>",
         "&7/trade <player>, /tradeaccept, /wormhole accept|deny, /token, /tag set <text> | /tag color <r> <g> <b>",
         "&7/chestshop price|currency ..., /jobs, /bounty <player> <amount> | /bounty list | /bounty cancel <player>",
         "&7/skills, /menu (all windows in one place), /collect",
         "&7/economy help · /economy boss spawn <mob> [hp] · /economy boss clear|list",
         "&7/fortuneandfavors config (toggle features & settings - OP or economy admin)"
      }) {
         ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal(Chat.colorize(line)), false);
      }

      return 1;
   }

   private static int economyReload(CommandContext<CommandSourceStack> ctx) {
      MinecraftServer server = ((CommandSourceStack)ctx.getSource()).getServer();
      boolean ok = loadSafely("block values reload", () -> BlockValues.load(server));
      ok = loadSafely("shop data reload", () -> ShopData.load(server)) && ok;
      if (!ok) {
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(() -> Component.literal(Chat.colorize("&cReload failed - check the console/log for the exact error (data left unchanged).")), true);
         return 0;
      } else {
         ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal(Chat.colorize("&aReloaded block values and shop data.")), true);
         return 1;
      }
   }

   private static boolean loadSafely(String what, Runnable loader) {
      try {
         loader.run();
         return true;
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: error during " + what, t);
         return false;
      }
   }

   private static int transferBalance(CommandContext<CommandSourceStack> ctx, ServerPlayer target, long amount) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "balance")) {
         return 0;
      } else if (player.getUUID().equals(target.getUUID())) {
         Chat.msg(player, "&cYou can't transfer money to yourself.");
         return 0;
      } else if (!EconomyManager.pay(player.getUUID(), target.getUUID(), amount)) {
         Chat.msg(player, "&cYou don't have enough money! Need " + Chat.moneyStr(amount));
         return 0;
      } else {
         SoundUtil.play(player, ModSounds.TRANSFER);
         Chat.raw(
            player,
            "§aTransferred "
               + Chat.moneyStr(amount)
               + "§a to §f"
               + target.getName().getString()
               + "§a. Balance: "
               + Chat.moneyStr(EconomyManager.balance(player.getUUID()))
         );
         Chat.raw(
            target,
            "§f"
               + player.getName().getString()
               + "§7 sent you "
               + Chat.moneyStr(amount)
               + "§7. Balance: "
               + Chat.moneyStr(EconomyManager.balance(target.getUUID()))
         );
         return 1;
      }
   }

   private static int economyGive(CommandContext<CommandSourceStack> ctx, ServerPlayer target, long amount, boolean take) throws CommandSyntaxException {
      if (take) {
         EconomyManager.takeCash(target.getUUID(), amount);
      } else {
         EconomyManager.addCash(target.getUUID(), amount);
      }

      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
               Chat.colorize("&a" + (take ? "Took " : "Gave ") + Chat.moneyStr(amount) + "&a " + (take ? "from" : "to") + " &f" + target.getName().getString())
            ),
            true
         );
      Chat.raw(target, "§eYour balance: " + Chat.moneyStr(EconomyManager.balance(target.getUUID())));
      return 1;
   }

   private static int economySet(CommandContext<CommandSourceStack> ctx, ServerPlayer target, long amount) throws CommandSyntaxException {
      EconomyManager.setBalance(target.getUUID(), amount);
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(() -> Component.literal(Chat.colorize("&aSet &f" + target.getName().getString() + "&a balance to " + Chat.moneyStr(amount))), true);
      Chat.raw(target, "§eYour balance: " + Chat.moneyStr(EconomyManager.balance(target.getUUID())));
      return 1;
   }

   private static int economyBalance(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
               Chat.colorize(
                  "&f"
                     + target.getName().getString()
                     + "&7 balance: "
                     + Chat.moneyStr(EconomyManager.balance(target.getUUID()))
                     + "&7 Pending items: &f"
                     + EconomyManager.collectionCount(target.getUUID())
               )
            ),
            false
         );
      return 1;
   }

   private static int economyTestAuction(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "auction")) {
         return 0;
      }

      List<ShopEntry> all = new ArrayList<>();

      for (Category c : Category.values()) {
         all.addAll(ShopData.entries(c));
      }

      if (all.isEmpty()) {
         Chat.msg(player, "&cNo shop items to test with.");
         return 0;
      } else {
         Random random = new Random();
         ShopEntry entry = all.get(random.nextInt(all.size()));
         ItemStack stack = entry.stack().copy();
         stack.setCount(1 + random.nextInt(16));
         boolean fixed = ModConfig.buyNow() && random.nextBoolean();
         long base = Math.max(1L, BlockValues.valueOf(stack));
         long price = fixed ? base * (2 + random.nextInt(8)) : base;
         long minutes = ModConfig.auctionMinutes();
         int id = AuctionManager.createAuction(
            player.getUUID(), player.getName().getString() + " (test)", stack, fixed ? "fixed" : "bid", price, 1L, minutes * 1200L, "cash"
         );
         AuctionManager.save(((CommandSourceStack)ctx.getSource()).getServer());
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(
               () -> Component.literal(
                  Chat.colorize(
                     "&aCreated test "
                        + (fixed ? "fixed" : "bid")
                        + " auction &d#"
                        + id
                        + "&a: &f"
                        + stack.getHoverName().getString()
                        + " x"
                        + stack.getCount()
                        + "&a for "
                        + Chat.moneyStr(price)
                        + " &7("
                        + minutes
                        + " min)"
                  )
               ),
               true
            );
         return id;
      }
   }

   private static int economyPriceMultiplier(CommandContext<CommandSourceStack> ctx, double multiplier) throws CommandSyntaxException {
      ShopData.setPriceMultiplier(multiplier);
      ShopData.save(((CommandSourceStack)ctx.getSource()).getServer());
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(Chat.colorize("&aShop price multiplier set to &f" + ShopData.priceMultiplier() + "&a (applies to every shop price)")), true
         );
      return 1;
   }

   private static int economyPriceOverride(CommandContext<CommandSourceStack> ctx, Item item, long price) throws CommandSyntaxException {
      ShopData.setPriceOverride(item, price);
      ShopData.save(((CommandSourceStack)ctx.getSource()).getServer());
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
               Chat.colorize(
                  "&aSet base price for &f"
                     + new ItemStack(item).getHoverName().getString()
                     + "&a to "
                     + Chat.moneyStr(price)
                     + "&a (effective: "
                     + Chat.moneyStr(ShopData.buyPrice(item))
                     + ")"
               )
            ),
            true
         );
      return 1;
   }

   private static int auctionList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "auction")) {
         return 0;
      }

      if (AuctionManager.activeAuctions().isEmpty()) {
         Chat.msg(player, "&7No active auctions. List your held item with &f/auction sell bid <start>");
         return 0;
      }

      Chat.raw(player, "§8[§dAuction§8]§7 Active auctions:");

      for (Auction a : AuctionManager.activeAuctions()) {
         String action = "fixed".equals(a.mode) ? "buy" : "bid";
         Chat.raw(
            player,
            "§d#"
               + a.id
               + "§7 "
               + a.item.getHoverName().getString()
               + " x"
               + a.item.getCount()
               + " by §f"
               + a.sellerName
               + "§7 "
               + a.mode
               + " "
               + AuctionManager.currencyString(a)
               + "§8 /auction "
               + action
               + " "
               + a.id
         );
      }

      return AuctionManager.count();
   }

   /**
    * Opens the window pre-filled with a search, so a player who knows what they want can skip the
    * typing-in-an-anvil step. Everything the searcher can spell, the command can spell too.
    */
   private static int auctionSearch(CommandContext<CommandSourceStack> ctx, String text) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "auction")) {
         return 0;
      }

      AuctionMenu.open(player, text, AuctionMenu.View.BROWSE);
      return 1;
   }

   /** The window's "my listings" view, for a player who wants their own stock rather than the market. */
   private static int auctionMine(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "auction")) {
         return 0;
      }

      AuctionMenu.open(player, "", AuctionMenu.View.MINE);
      return 1;
   }

   /** The window's "my bids" view - everything this player's money is standing in. */
   private static int auctionBids(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "auction")) {
         return 0;
      }

      AuctionMenu.open(player, "", AuctionMenu.View.BIDS);
      return 1;
   }

   private static int auctionCreate(CommandContext<CommandSourceStack> ctx, String mode, long price, long minIncrement, long durationTicks, String currency) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "auction")) {
         return 0;
      }

      ItemStack held = player.getMainHandItem();
      if (held.isEmpty()) {
         Chat.msg(player, "&cHold the item you want to auction.");
         return 0;
      }

      if (AuctionManager.isUnauctionable(held)) {
         Chat.msg(player, "&cThat item can't be auctioned.");
         return 0;
      }

      if ("fixed".equals(mode) && !ModConfig.buyNow()) {
         Chat.msg(player, "&cBuy-now auctions are disabled on this server (/fortuneandfavors config).");
         return 0;
      }

      String cleanCurrency = normalizeCurrency(currency);
      if (cleanCurrency == null) {
         Chat.msg(player, "&cCurrency must be &fcash&c or an item id like &fminecraft:diamond&c.");
         return 0;
      }

      ItemStack auctioned = held.copy();
      held.setCount(0);
      if (ModItems.isSpawnerItem(auctioned)) {
         ModItems.unbindSpawner(auctioned);
      }

      int id = AuctionManager.createAuction(player.getUUID(), player.getName().getString(), auctioned, mode, price, minIncrement, durationTicks, cleanCurrency);
      AuctionManager.save(((CommandSourceStack)ctx.getSource()).getServer());
      SoundUtil.play(player, ModSounds.AUCTION_CREATE);
      Chat.raw(player, "§aCreated " + mode + " auction §d#" + id + "§a for " + auctioned.getHoverName().getString() + " x" + auctioned.getCount() + ".");
      return id;
   }

   private static int auctionBid(CommandContext<CommandSourceStack> ctx, int id, long amount) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "auction")) {
         return 0;
      }

      Auction auction = AuctionManager.get(id);
      if (auction != null && !auction.finished) {
         if (!AuctionManager.bid(((CommandSourceStack)ctx.getSource()).getServer(), player, auction, amount)) {
            Chat.msg(player, "&cBid failed. Check price, balance, currency, and whether you are already winning.");
            return 0;
         } else {
            SoundUtil.play(player, ModSounds.AUCTION_BID);
            Chat.raw(player, "§aBid placed on auction §d#" + id + "§a.");
            return 1;
         }
      } else {
         Chat.msg(player, "&cAuction not found.");
         return 0;
      }
   }

   private static int auctionBuy(CommandContext<CommandSourceStack> ctx, int id) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "auction")) {
         return 0;
      }

      Auction auction = AuctionManager.get(id);
      if (auction != null && !auction.finished) {
         if (!AuctionManager.buyFixed(((CommandSourceStack)ctx.getSource()).getServer(), player, auction)) {
            Chat.msg(player, "&cCould not buy this auction.");
            return 0;
         } else {
            SoundUtil.play(player, ModSounds.AUCTION_BID);
            Chat.raw(player, "§aBought auction §d#" + id + "§a. Use §f/auction claim§a.");
            return 1;
         }
      } else {
         Chat.msg(player, "&cAuction not found.");
         return 0;
      }
   }

   private static int auctionCancel(CommandContext<CommandSourceStack> ctx, int id) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "auction")) {
         return 0;
      }

      Auction auction = AuctionManager.get(id);
      if (auction != null && !auction.finished) {
         if (!AuctionManager.cancelAuction(((CommandSourceStack)ctx.getSource()).getServer(), player, auction)) {
            Chat.msg(player, "&cOnly the seller can cancel this auction.");
            return 0;
         } else {
            AuctionManager.save(((CommandSourceStack)ctx.getSource()).getServer());
            Chat.raw(player, "§aCancelled auction §d#" + id + "§a.");
            return 1;
         }
      } else {
         Chat.msg(player, "&cAuction not found.");
         return 0;
      }
   }

   private static int setChestShopPrice(CommandContext<CommandSourceStack> ctx, Item item, long price) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "exclusive")) {
         return 0;
      }

      ChestShop shop = lookedAtShop(player);
      if (shop == null) {
         Chat.msg(player, "&cLook at your chest shop first.");
         return 0;
      }

      if (!shop.owner.equals(player.getUUID())) {
         Chat.msg(player, "&cOnly the shop owner can edit this shop.");
         return 0;
      }

      if (item == null) {
         shop.prices.put("*", price);
         Chat.msg(player, "&aDefault shop price set to " + Chat.moneyStr(price));
      } else {
         shop.prices.put(itemId(item), price);
         Chat.msg(player, "&aPrice set for &f" + new ItemStack(item).getHoverName().getString() + "&a: " + Chat.moneyStr(price));
      }

      ChestShopManager.save(((CommandSourceStack)ctx.getSource()).getServer());
      return 1;
   }

   private static int setChestShopCurrency(CommandContext<CommandSourceStack> ctx, String currency) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "exclusive")) {
         return 0;
      } else {
         ChestShop shop = lookedAtShop(player);
         if (shop == null) {
            Chat.msg(player, "&cLook at your chest shop first.");
            return 0;
         } else if (!shop.owner.equals(player.getUUID())) {
            Chat.msg(player, "&cOnly the shop owner can edit this shop.");
            return 0;
         } else {
            shop.currency = currency;
            ChestShopManager.save(((CommandSourceStack)ctx.getSource()).getServer());
            Chat.msg(player, "&aShop currency set to &f" + currency);
            return 1;
         }
      }
   }

   /** The chat equivalent of the GUI's MODE button: buy -> sell -> closed -> buy.
    *  Reports the new state so a closed shop can never happen silently. */
   private static int toggleChestShop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "exclusive")) {
         return 0;
      }

      ChestShop shop = lookedAtShop(player);
      if (shop == null) {
         Chat.msg(player, "&cLook at your chest shop first.");
         return 0;
      }

      if (!shop.owner.equals(player.getUUID())) {
         Chat.msg(player, "&cOnly the shop owner can change this shop.");
         return 0;
      }

      shop.type = ChestShopManager.nextType(shop.type);
      ChestShopManager.save(((CommandSourceStack)ctx.getSource()).getServer());
      Chat.msg(player, "&aShop mode: &f" + ChestShopManager.typeName(shop.type)
         + (ChestShopManager.TYPE_OFF.equals(shop.type)
            ? " &7- nobody can trade here, but your prices and stock are kept."
            : " &7- customers see it immediately."));
      return 1;
   }

   /** Prints a shop's recent trades to chat - the "what sells and when" view,
    *  for owners who would rather read it than open the Sales Ledger screen. */
   private static int showChestShopLedger(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "exclusive")) {
         return 0;
      }

      ChestShop shop = lookedAtShop(player);
      if (shop == null) {
         Chat.msg(player, "&cLook at your chest shop first.");
         return 0;
      }

      if (!shop.owner.equals(player.getUUID())) {
         Chat.msg(player, "&cOnly the shop owner can view this shop's ledger.");
         return 0;
      }

      java.util.List<ChestShopManager.LedgerEntry> entries = ChestShopManager.recent(shop, count);
      Chat.raw(player, "&6&lSales Ledger &7- " + entries.size() + " trade" + (entries.size() == 1 ? "" : "s"));
      if (entries.isEmpty()) {
         Chat.msg(player, "&7No sales recorded yet.");
         return 1;
      }

      for (ChestShopManager.LedgerEntry entry : entries) {
         boolean sold = ChestShopManager.LEDGER_SOLD.equals(entry.kind);
         // A sale reads in the shop's currency; a purchase reads as the cash value
         // of the goods taken in, since that is what it cost the owner either way.
         boolean saleInItems = sold && shop.isItemCurrency();
         String amount = saleInItems ? entry.total + "x " + currencyNameOf(shop) : Chat.moneyStr(entry.total);
         Chat.raw(
            player,
            (sold ? "&a+ " : "&e- ")
               + "&f"
               + entry.qty
               + "x "
               + entry.name
               + "&7 "
               + (sold ? "sold to " : "bought from ")
               + "&f"
               + entry.who
               + (sold ? "&7 for &a" : "&7 - goods worth &a")
               + amount
               + " &8("
               + ChestShopManager.ago(entry.time)
               + ")"
         );
      }

      java.util.List<ChestShopManager.ItemTotal> top = ChestShopManager.topItems(shop, 3);
      if (!top.isEmpty()) {
         StringBuilder line = new StringBuilder("&7Top sellers: ");
         for (int i = 0; i < top.size(); i++) {
            if (i > 0) {
               line.append("&8, ");
            }

            line.append("&f").append(top.get(i).name()).append(" &7x").append(top.get(i).qty());
         }

         Chat.raw(player, line.toString());
      }

      return 1;
   }

   /** Turns the shop's out-of-stock chat alerts on or off. */
   private static int toggleChestShopAlerts(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "exclusive")) {
         return 0;
      }

      ChestShop shop = lookedAtShop(player);
      if (shop == null) {
         Chat.msg(player, "&cLook at your chest shop first.");
         return 0;
      }

      if (!shop.owner.equals(player.getUUID())) {
         Chat.msg(player, "&cOnly the shop owner can change this shop.");
         return 0;
      }

      shop.restockAlerts = !shop.restockAlerts;
      ChestShopManager.save(((CommandSourceStack)ctx.getSource()).getServer());
      Chat.msg(
         player,
         shop.restockAlerts
            ? "&aRestock alerts on. &7You'll be warned when a buy shop sells out."
            : "&cRestock alerts off. &7Sales are still recorded in the ledger."
      );
      return 1;
   }

   private static String currencyNameOf(ChestShop shop) {
      if (shop.isTokenCurrency()) {
         return "Token";
      }

      Item item = shop.currencyItem();
      return item != null && item != Items.AIR ? new ItemStack(item).getHoverName().getString() : "?";
   }

   /** Deletes a shop, leaving the chest an ordinary (breakable) chest. Needs
    *  {@code confirm} because the prices and settings are not recoverable. */
   private static int deleteChestShop(CommandContext<CommandSourceStack> ctx, boolean confirmed) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (!featureOn(player, "exclusive")) {
         return 0;
      }

      ChestShop shop = lookedAtShop(player);
      if (shop == null) {
         Chat.msg(player, "&cLook at your chest shop first.");
         return 0;
      }

      if (!shop.owner.equals(player.getUUID())) {
         Chat.msg(player, "&cOnly the shop owner can delete this shop.");
         return 0;
      }

      if (!confirmed) {
         Chat.msg(player, "&eThis deletes the shop (prices and settings are lost; the chest and its contents stay).");
         Chat.msg(player, "&7Run &f/chestshop delete confirm&7 to go ahead.");
         return 1;
      }

      BlockPos pos = lookedAtShopPos(player);
      if (pos == null) {
         Chat.msg(player, "&cLook at your chest shop first.");
         return 0;
      }

      String key = ChestShopManager.keyAt(player.level(), pos);
      ChestShopManager.delete(key == null ? ChestShopManager.keyFor(player.level(), pos) : key, ((CommandSourceStack)ctx.getSource()).getServer());
      Chat.msg(player, "&cShop deleted. &7The chest is an ordinary chest again and can be broken.");
      return 1;
   }

   private static int openConfigGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      boolean admin = source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
         || source.getPlayer() != null && PermissionManager.isEconomyAdmin(source.getPlayer().getUUID());
      if (!admin) {
         Chat.msg(source.getPlayerOrException(), "&cOnly an admin can open the config.");
         return 0;
      } else {
         FeatureConfigMenu.open(((CommandSourceStack)ctx.getSource()).getPlayerOrException());
         return 1;
      }
   }

   private static int ffGive(CommandContext<CommandSourceStack> ctx, String raw) throws CommandSyntaxException {
      ServerPlayer giver = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      ServerPlayer recipient = giver;
      String name = raw.trim();
      net.minecraft.core.HolderLookup.Provider holders = ((CommandSourceStack)ctx.getSource()).getServer().registryAccess();
      if (!"all".equalsIgnoreCase(name)) {
         ItemStack stack = modItem(name, holders);
         if (stack == null) {
            stack = modItemByDisplayName(name, holders);
         }

         if (stack == null) {
            int sp = name.lastIndexOf(32);
            if (sp > 0) {
               String head = name.substring(0, sp);
               String tail = name.substring(sp + 1);
               ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getServer().getPlayerList().getPlayerByName(tail);
               if (p != null) {
                  recipient = p;
                  stack = modItem(head, holders);
                  if (stack == null) {
                     stack = modItemByDisplayName(head, holders);
                  }
               }
            }
         }

         if (stack == null) {
            ((CommandSourceStack)ctx.getSource())
               .sendSuccess(
                  () -> Component.literal(
                     Chat.colorize(
                        "&cUnknown item '&f"
                           + name
                           + "&c'. Aliases: &f"
                           + String.join(", ", giveAliases(holders))
                           + "&c. You can also use the full item name, e.g. &f/ff give King Loot Box&c."
                     )
                  ),
                  false
               );
            return 0;
         } else {
            ServerPlayer to = recipient;
            ItemStack give = stack;
            giveCatalogItem(to, give);
            ((CommandSourceStack)ctx.getSource())
               .sendSuccess(() -> Component.literal(Chat.colorize("&aGave &f" + give.getHoverName().getString() + "&a to &f" + to.getName().getString())), true);
            return 1;
         }
      } else {
         ServerPlayer to = recipient;

         for (ItemStack s : allModItems(holders)) {
            giveCatalogItem(to, s);
         }

         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(() -> Component.literal(Chat.colorize("&aGave every Fortune & Favors item to &f" + to.getName().getString())), true);
         return 1;
      }
   }

   public static String datapackGiveFunction(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         CustomData custom = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
         if (custom != null && !custom.isEmpty()) {
            CompoundTag tag = custom.copyTag();
            if (tag.contains("Orbital_Cannon")) {
               return switch (tag.getIntOr("Orbital_Cannon", -1)) {
                  case 1 -> "fortuneandfavors:get_nuke";
                  case 2 -> "fortuneandfavors:get_stab";
                  case 3 -> "fortuneandfavors:get_lawnuke";
                  case 4 -> "fortuneandfavors:get_wither_nuke";
                  default -> null;
               };
            } else {
               return tag.contains("wolf_cannon") ? "fortuneandfavors:get_wolf_rod" : null;
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   public static void giveCatalogItem(ServerPlayer to, ItemStack stack) {
      InventoryHelper.giveOrDrop(to, stack);
   }

   private static ItemStack modItemByDisplayName(String input, net.minecraft.core.HolderLookup.Provider holders) {
      String want = normalizeName(input);

      for (ItemStack s : allModItems(holders)) {
         if (normalizeName(s.getHoverName().getString()).equals(want)) {
            return s;
         }
      }

      return null;
   }

   private static String normalizeName(String s) {
      StringBuilder sb = new StringBuilder();

      for (int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         if (c == 167) {
            i++;
         } else if (Character.isLetterOrDigit(c)) {
            sb.append(Character.toLowerCase(c));
         }
      }

      return sb.toString();
   }

   public static String aliasFor(ItemStack stack) {
      if (ModItems.isMindAscended(stack)) {
         return "multidimensional";
      }

      if (CCEnchantments.isBoltBringerBook(stack)) {
         // Level-aware alias: /ff give boltbringer2 (also works as "boltbringer 2").
         return "boltbringer" + CCEnchantments.boltBringerLevel(stack);
      }

      // Level-aware alias for the Pocket-Watch's two versions, and for tomes:
      // /ff give pocketwatch2 (also works as "pocketwatch 2" where applicable).
      if (ModItems.isPocketWatch(stack)) {
         return ModItems.pocketWatchVersion(stack) >= 2 ? "pocketwatch2" : "pocketwatch";
      }

      if (CustomEnchantments.isTome(stack)) {
         // Level-aware alias: /ff give lifestealtome3 (also works as "lifesteal 3").
         String type = ModItems.typeOf(stack);
         String base = switch (type) {
            case "lifesteal_tome" -> "lifestealtome";
            case "sticky_tome" -> "stickytome";
            case "seismic_tome" -> "seismictome";
            case "mindwrack_tome" -> "mindwracktome";
            case "frostbite_tome" -> "frostbitetome";
            case "aging_tome" -> "agingtome";
            case "overclock_tome" -> "overclocktome";
            case "starfall_tome" -> "starfalltome";
            case "voidrend_tome" -> "voidrendtome";
            case "levy_tome" -> "levytome";
            default -> normalizeName(stack.getHoverName().getString());
         };
         return base + CustomEnchantments.tomeLevel(stack);
      }

      String type = ModItems.typeOf(stack);
      if (type == null) {
         return normalizeName(stack.getHoverName().getString());
      }

      return switch (type) {
         case "chunk_claimer" -> "claimer";
         case "buy_sign" -> "buysign";
         case "sell_sign" -> "sellsign";          case "auto_sell_hopper" -> "autosell";
          case "upwards_hopper" -> "upwardshopper";
          case "elevator" -> "elevator";
         case "token_redeemer" -> "redeemer";
         case "spawner_infuser" -> "infuser";
         case "item_forge" -> "itemforge";
         case "wormhole_potion" -> "wormhole";
         case "mystery_box" -> "mystery";
         case "raid_boss_token" -> "raidtoken";
         case "king_loot_box" -> "kingloot";
         case "wither_loot_box" -> "witherloot";
         case "raid_loot_box" -> "raidlootbox";
         case "slime_loot_box" -> "slimebox";
         case "wither_staff" -> "witherstaff";
         case "wither_blade" -> "witherblade";
         case "wither_crown" -> "withercrown";
         case "wither_cloak_sword" -> "cloaksword";
         case "king_bone" -> "essence";
         case "slime_boss_token" -> "slimetoken";
         case "slime_launcher" -> "slimelauncher";
         case "slime_shield" -> "slimeshield";
         case "slime_boots" -> "slimeboots";
         case "slime_core" -> "gelatin";
         case "stone_golem_token" -> "golemtoken";
         case "golem_core" -> "golemcore";
         case "golem_loot_box" -> "golembox";
         case "stone_staff" -> "stonestaff";
         case "golem_fist" -> "golemfist";
         case "stoneheart" -> "stoneheart";
         case "mind_loot_box" -> "mindbox";
         case "mindbinder_eye" -> "mindbinder";
         case "shattered_mind" -> "shatteredmind";
         case "mind_staff" -> "mindstaff";
         case "possessed_mask" -> "possessedmask";
         case "mind_shroud" -> "mindshroud";
         case "snow_queen_token" -> "snowqueen";
         case "snow_loot_box" -> "snowlootbox";
         case "time_lord_loot_box" -> "timelordloot";
         case "space_time_rift" -> "spacetimerift";
         case "scarlet_blood" -> "scarletblood";
         case "scarlet_loot_box" -> "scarletlootbox";
         case "scarlet_trophy" -> "scarlettrophy";
         case "bloodsoaked_core" -> "bloodcore";
         case "scarlets_fang" -> "scarletfang";
         case "scarlet_grimoire" -> "scarletgrimoire";
         case "blood_prism" -> "bloodprism";
         case "clockwork_core" -> "clockworkcore";
         case "clockwork_loot_box" -> "clockworklootbox";
         case "clockwork_trophy" -> "clockworktrophy";
         case "mech_scrap" -> "mechscrap";
         case "clockwork_gauntlet" -> "clockworkgauntlet";
         case "mechanical_heart" -> "mechanicalheart";
         case "automaton_armor" -> "automatonarmor";
         case "astral_compass" -> "astralcompass";
         case "starbound_loot_box" -> "starboundlootbox";
         case "starbound_trophy" -> "starboundtrophy";
         case "magical_essence" -> "magicalessence";
         case "starpiercer" -> "starpiercer";
         case "astral_mantle" -> "astralmantle";
         case "magisters_codex" -> "magisterscodex";
         // The two newest fights. "sovereigns_crown" already answers to "sovereign", and the sea
         // boss's summon is not a crown, so it is named for the sea instead of the throne.
         case "sovereigns_heart" -> "seasovereign";
         case "drowned_loot_box" -> "drownedlootbox";
         case "leviathans_grasp" -> "grasp";
         case "tidecaller" -> "tidecaller";
         case "abyssal_chain" -> "abyssalchain";
         case "gale_sigil" -> "galesigil";
         case "gale_loot_box" -> "galelootbox";
         case "skybreaker" -> "skybreaker";
         case "gale_chakram" -> "galechakram";
         // "mantle" is the Astral Mantle's, so the Warden's is only ever spoken in full.
         case "wardens_mantle" -> "wardensmantle";
         // The two forge materials, one per new boss. Named for their own sets rather than for
         // "material", which every boss in the mod would answer to.
         case "abyssal_pearl" -> "abyssalpearl";
         case "gale_core" -> "galecore";
         case "void_anchor" -> "voidanchor";
         case "voidshaper_loot_box" -> "voidshaperlootbox";
         case "colossus_trophy" -> "colossustrophy";
         case "voidsteel_scrap" -> "voidsteelscrap";
         case "void_reaver" -> "voidreaver";
         case "colossus_plate" -> "colossusplate";
         case "shaping_sigil" -> "shapingsigil";
         case "sovereigns_crown" -> "sovereignscrown";
         case "sovereign_loot_box" -> "sovereignlootbox";
         case "sovereign_trophy" -> "sovereigntrophy";
         case "royal_tribute" -> "royaltribute";
         case "royal_contract" -> "royalcontract";
         case "sovereigns_bell" -> "sovereignsbell";
         case "emerald_seal" -> "emeraldseal";
         case "chrono_shard" -> "chronoshard";
         case "hourglass_of_haste" -> "hourglass";
         case "ice_staff" -> "icestaff";
         case "frozen_heart" -> "frozenheart";
         case "frostbound_crown" -> "frostboundbow";
         case "glacier_cloak" -> "glaciercloak";
         case "sculk_medallion" -> "medallion";
         case "sculk_loot_box" -> "sculkbox";
         case "sculk_mage_staff" -> "sculkstaff";
         case "sculk_sensor_leggings" -> "sculkleggings";
         case "wardens_call" -> "wardenscall";
         case "sculk_essence" -> "sculkessence";
         case "repair_membrane" -> "membrane";
         case "mystery_key" -> switch (com.fortuneandfavors.economy.MysteryChestManager.keyTier(stack)) {
            case 0 -> "commonkey";
            case 1 -> "rarekey";
            case 2 -> "epickey";
            default -> "legendarykey";
         };
         case "distant_memory_shard" -> "memoryshard";
         case "distant_memory_sword" -> "distantmemory";
         case "last_remembrance" -> "lastremembrance";
         // The Ender Dragon's set. The two materials and the three weapons they forge
         // into; the ids are the model ids the art pack dispatches on.
         case "heart_of_the_end" -> "heartoftheend";
         case "dragon_scale" -> "dragonscale";
         case "voidfang" -> "voidfang";
         case "starfall" -> "starfall";
         case "enderheart" -> "enderheart";
         // The awakened tier has its own art, so it has its own model id.
         case "voidfang_awakened" -> "voidfangawakened";
         case "starfall_awakened" -> "starfallawakened";
         case "enderheart_awakened" -> "enderheartawakened";
         case "warlord_axe" -> "warlordaxe";
         case "evoker_spellbook" -> "evokerspellbook";
         case "captain_horn" -> "captainhorn";
         case "warlord_cloak" -> "warlordcloak";
         case "evoker_cloak" -> "evokercloak";
         case "illusioner_spellbook" -> "illusionerspellbook";
         case "illusioner_cloak" -> "illusionercloak";
         case "raiders_item_upgrader" -> "raidersupgrader";
         case "backpack" -> "backpack";
         case "warlord_trophy" -> "warlordtrophy";
         case "rune_haste" -> "runehaste";
         case "rune_flame" -> "runeflame";
         case "ff_chainfire" -> "chainfire";
         case "ff_sniper" -> "sniper";
         case "ff_revolver" -> "revolver";
         case "ff_sharpshooter" -> "sharpshooter";
         case "ff_recoil" -> "recoil";
         case "ff_hunters_mark" -> "huntersmark";
         case "ff_heavy_bolt" -> "heavybolt";
         case "ff_curse_fragility" -> "fragility";
         case "ff_combo" -> "combo";
         case "ff_berserker" -> "berserker";
         case "rune_fortune" -> "runefortune";
         case "rune_swiftness" -> "runeswiftness";
         case "rune_frost" -> "runefrost";
         case "raid_banner" -> "raidbanner";
         case "seismic_tome" -> "seismictome";
         case "mindwrack_tome" -> "mindwracktome";
         case "frostbite_tome" -> "frostbitetome";
         default -> normalizeName(stack.getHoverName().getString());
      };
   }

   private static int explosionRebuild(CommandContext<CommandSourceStack> ctx, Boolean force) {
      MinecraftServer server = ((CommandSourceStack)ctx.getSource()).getServer();
      if (force == null) {
         // A bare command REPORTS. It used to flip the switch, so an admin who
         // ran it just to read the setting silently turned the feature off -
         // and then reported that explosion rebuild was broken.
         boolean on = ModConfig.explosionRebuild();
         int queued = com.fortuneandfavors.economy.ExplosionRebuildManager.pendingCount();
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(
               () -> Component.literal(
                  Chat.colorize(
                     "&6&lExplosion Rebuild &r"
                        + (on ? "&aON" : "&4OFF")
                        + "\n&7When ON, blocks destroyed by TNT, creepers, raid bosses and boss craters are queued and put back about five seconds later (never inside a claim).\n&7Queued for rebuild right now: &f"
                        + queued
                        + "\n&7Change it with &f/explosionrebuild on&7 or &f/explosionrebuild off&7."
                  )
               ),
               false
            );
         return 1;
      }
      boolean now = force;
      ModConfig.setExplosionRebuild(now);
      ModConfig.save(server);
      Component msg = Component.literal(
         now
            ? "§aExplosion Rebuild is now §2ON§a - blast zones rebuild after a few seconds."
            : "§cExplosion Rebuild is now §4OFF§c - explosions destroy blocks normally."
      );
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> msg, false);
      return 1;
   }

   private static int raidCooldown(CommandContext<CommandSourceStack> ctx, Boolean force) {
      MinecraftServer server = ((CommandSourceStack)ctx.getSource()).getServer();
      if (force == null) {
         boolean on = ModConfig.raidCooldown();
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(
               () -> Component.literal(
                  Chat.colorize(
                     "&6&lRaid Cooldown &r"
                        + (on ? "&aON" : "&4OFF")
                        + "\n&7When ON, player raids have a 30 minute cooldown.\n&7Change it with &f/raidcooldown on&7 or &f/raidcooldown off&7."
                  )
               ),
               false
            );
         return 1;
      }
      boolean now = force;
      ModConfig.setRaidCooldown(now);
      ModConfig.save(server);
      Component msg = Component.literal(
         now
            ? "§aRaid Cooldown is now §2ON§a - player raids have a 30 minute cooldown."
            : "§cRaid Cooldown is now §4OFF§c - player raids can be triggered without cooldown."
      );
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> msg, false);
      return 1;
   }

   private static int ffTestCorruption(CommandContext<CommandSourceStack> ctx, int stage) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String err = BossManager.testCorruption(p, stage);
         if (err != null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
            return 0;
         } else {
            ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("§aApplied test corruption stage " + stage + " for 20s."), false);
            return 1;
         }
      }
   }

   private static int ffTestSnowQueen(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String err = BossManager.spawnSnowQueen(p);
         if (err != null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
            return 0;
         } else {
            return 1;
         }
      }
   }

   /**
    * {@code /ff test prison loosebrick} - force a break-out, staff only, prison only.
    *
    * <p>The loose brick is a random work of the wall, so it is the one part of the escape loop a
    * person cannot stage by wanting it. This puts one at the caller - through the same door the
    * mining roll uses, particles and all, so what is seen nearby is the real thing - and refuses
    * anywhere but the block.
    */
   private static int ffTestPrisonLooseBrick(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }
      String err = com.fortuneandfavors.economy.PrisonCellblock.forceLooseBrick(p);
      if (err != null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal(Chat.colorize("&c" + err)));
         return 0;
      }
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(() -> Component.literal(Chat.colorize("&a&l[TEST]&r &fA loose brick has worked free of the wall beside you.")), false);
      return 1;
   }

   /**
    * {@code /ff test prison skipwait} - drop the maximum-security wait, staff only.
    *
    * <p>Both halves of the wait a person actually waits through: a live sentence in the block (the
    * room is sealed and the sentence dropped, with nothing paid out), and the lockout that follows
    * being thrown out of the block. See {@code PrisonCellblock.skipWait} for what is deliberately
    * <i>not</i> done - no haul is taken, no money is paid, and the prisoner stays in the block.
    */
   private static int ffTestPrisonSkipWait(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }
      String err = com.fortuneandfavors.economy.PrisonCellblock.skipWait(p);
      if (err != null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal(Chat.colorize("&c" + err)));
         return 0;
      }
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(Chat.colorize("&a&l[TEST]&r &fThe wait is gone - the room is sealed and the lockout is clear.")),
            false
         );
      return 1;
   }

   private static int ffTestBetrayal(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         PlayerRaidManager.testBetrayal(p);
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(
               () -> Component.literal("§cThe Warlord's offer appears - accept it to test the betrayal flow."),
               false
            );
         return 1;
      }
   }

   /**
    * {@code /ff test puppet <1-3>} - the threading mechanic on demand.
    *
    * <p>Spawns the Puppeteer if there is not one already and puts the asked-for number
    * of strings on the caller, so the whole escalation can be felt without fighting him
    * down to phase three first. One and two land immediately because they are just
    * pulls; three goes through the <b>real windup</b>, so the command exercises the tell
    * and the hold out of the same run rather than bypassing the tell to reach the hold.
    */
   private static int ffTestPuppet(CommandContext<CommandSourceStack> ctx, int strings) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }
      String err = com.fortuneandfavors.economy.PuppeteerManager.testStrings(p, strings);
      if (err != null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal(Chat.colorize("&c" + err)));
         return 0;
      }
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
               Chat.colorize(
                  "&5&l[TEST]&r &fPuppeteer strings set to &d" + strings
                     + "&7. &8Three winds up first - watch the bar, then try running or hitting him."
               )
            ),
            false
         );
      return 1;
   }

   /**
    * {@code /ff anticheat tune} - the dials that are safe to move, and only those.
    *
    * <p>The line this command draws is the module's whole safety argument: it reports
    * and sets <b>policy</b> (how loudly to react) and has no access at all to <b>physics</b>
    * (what is possible). Reach margins, speed models and placement geometry decide
    * whether a player is cheating and are pinned by the self-test; if they were
    * settable from here, the fastest way to false-ban somebody would be a typo in this
    * command. Enforcement thresholds are server taste and are settable, and every one
    * is clamped to a range that no setting can push below a single ambiguous event.
    */
   private static int ffAnticheatTune(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      source.sendSuccess(
         () -> Component.literal(
            Chat.colorize("&c&l[AC]&r &fEnforcement policy &8(&7these are actionable, and clamped&8)")
         ),
         false
      );
      for (String line : com.fortuneandfavors.anticheat.AntiCheatPolicy.describe().split("\n")) {
         if (!line.isBlank()) {
            source.sendSuccess(() -> Component.literal(Chat.colorize(line)), false);
         }
      }
      source.sendSuccess(
         () -> Component.literal(
            Chat.colorize(
               "&8Physics (reach, speed model, placement geometry) is deliberately not listed and not settable: "
                  + "&7/ff anticheat tune set <key> <value>&8, &7/ff anticheat tune reset <key>"
            )
         ),
         false
      );
      return 1;
   }

   private static int ffAnticheatTuneSet(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      String key = StringArgumentType.getString(ctx, "key");
      double value = DoubleArgumentType.getDouble(ctx, "value");
      if (!com.fortuneandfavors.anticheat.AntiCheatPolicy.knows(key)) {
         source.sendFailure(
            Component.literal(
               Chat.colorize(
                  "&c&l[AC]&r &cNo such dial: &f"
                     + key
                     + "&7. Physics constants are not tunable by design - see &f/ff anticheat tune&7."
               )
            )
         );
         return 0;
      }
      double stored = com.fortuneandfavors.anticheat.AntiCheatPolicy.set(key, value);
      com.fortuneandfavors.anticheat.AntiCheatPolicy.save(source.getServer());
      var dial = com.fortuneandfavors.anticheat.AntiCheatPolicy.dial(key);
      boolean clamped = Math.abs(stored - value) > 1.0E-9;
      source.sendSuccess(
         () -> Component.literal(
            Chat.colorize(
               "&c&l[AC]&r &f"
                  + key
                  + " &8= &a"
                  + dial.show(stored)
                  + (clamped
                     ? "&7 (asked for &f" + value + "&7, clamped to the safe range &f" + dial.show(dial.min()) + "-" + dial.show(dial.max()) + "&7)"
                     : "&7. ")
                  + " &8- "
                  + dial.note()
            )
         ),
         true
      );
      return 1;
   }

   private static int ffAnticheatTuneReset(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      String key = StringArgumentType.getString(ctx, "key");
      if (!com.fortuneandfavors.anticheat.AntiCheatPolicy.knows(key) && !key.equals("all")) {
         source.sendFailure(Component.literal(Chat.colorize("&c&l[AC]&r &cNo such dial: &f" + key)));
         return 0;
      }
      com.fortuneandfavors.anticheat.AntiCheatPolicy.reset(key);
      com.fortuneandfavors.anticheat.AntiCheatPolicy.save(source.getServer());
      source.sendSuccess(
         () -> Component.literal(Chat.colorize("&c&l[AC]&r &fReset &a" + key + "&f to this build's tested value.")),
         true
      );
      return 1;
   }

   private static int ffTestIllusion(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String err = BossManager.testIllusion(p);
         if (err != null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
            return 0;
         } else {
            boolean twisted = BossManager.corruptionOf(p.getUUID()) >= 2;
            ((CommandSourceStack)ctx.getSource())
               .sendSuccess(
                  () -> Component.literal(
                     Chat.colorize(
                        twisted
                           ? "&5Your corruption twists the illusion - &fThe Mindbinder&5 stands where a player should be!"
                           : "&aIllusion summoned - a fake player walks beside you."
                     )
                  ),
                  false
               );
            return 1;
         }
      }
   }

   private static String[] giveAliases(net.minecraft.core.HolderLookup.Provider holders) {
      ItemStack[] items = allModItems(holders);
      String[] aliases = new String[items.length];

      for (int i = 0; i < items.length; i++) {
         aliases[i] = aliasFor(items[i]);
      }

      return aliases;
   }

   /** Every boss name (and alias) `/raidboss help <boss>` accepts - one list,
    *  used by both the in-game page lookup and the console fallback, so the two
    *  can never disagree about which bosses exist. */
   private static final Set<String> RAIDBOSS_KEYS = Set.of(
      "king", "wither", "kingwither", "witherskeleton",
      "slime", "slimeking",
      "golem", "stone", "stonegolem",
      "mindbinder", "mind", "eye", "mindbinder_eye",
      "snow", "snowqueen", "icequeen", "snow_queen",
      "sculk", "elderwarden", "warden",
      "timelord", "time_lord", "timelordtoken", "rift", "spacetime", "spacetime_rift", "space_time_rift",
      "scarlet", "scarletdevil", "scarlet_devil", "devil", "remilia", "scarletblood", "scarlet_blood",
      "clockwork", "clockworkking", "clockwork_king", "cog",
      "magister", "starbound", "starboundmagister",
      "voidshaper", "void_shaper", "shaper", "colossus",
      "sovereign", "emeraldsovereign", "emerald_sovereign", "emeraldking",
      "puppeteer", "puppet", "marionette", "strings", "emptymask", "empty_mask", "puppetmaster"
   );

   /** Exposed so the self-test can prove every listed name still opens a page. */
   public static Set<String> raidBossKeys() {
      return RAIDBOSS_KEYS;
   }

   /**
    * One row of `/raidboss help`'s own list: the name it is listed under, and what that boss is.
    *
    * <p>The list is the window on a terminal, and it was missing the two pages that were added
    * last - the Elder Warden and the Puppeteer - so a console user could read every summon
    * recipe but theirs. It is one row per boss now, and the self-test insists every page the
    * help window can open is named here: a recipe nobody is told about is a recipe nobody finds.
    */
   public record RaidBossRow(String key, String text) {
   }

   /** Every row of the terminal list, in the order it is printed in. */
   public static final List<RaidBossRow> RAIDBOSS_HELP_ROWS = List.of(
      new RaidBossRow("king", "the King Wither Skeleton - endgame, wither gear"),
      new RaidBossRow("slime", "the Slime King - early-mid game, slime gear"),
      new RaidBossRow("golem", "the Stone Golem - early game, stone gear"),
      new RaidBossRow("mindbinder", "the Mindbinder - mid game, mind gear"),
      new RaidBossRow("snow", "the Snow Queen - endgame, ice gear"),
      new RaidBossRow("sculk", "the Elder Warden - endgame, sculk gear"),
      new RaidBossRow("timelord", "the Time Lord - endgame, chrono gear"),
      new RaidBossRow("scarlet", "the Scarlet Devil - endgame, blood gear"),
      new RaidBossRow("clockwork", "the Clockwork King - endgame, machine gear"),
      new RaidBossRow("magister", "the Starbound Magister - endgame, astral gear"),
      new RaidBossRow("voidshaper", "the Void Shaper - endgame, block gear"),
      new RaidBossRow("sovereign", "the Emerald Sovereign - endgame, court gear"),
      new RaidBossRow("puppeteer", "the Puppeteer - endgame, string gear")
   );

   /** The rows of {@link #RAIDBOSS_HELP_ROWS} as the terminal page prints them. */
   private static String raidBossHelpList() {
      StringBuilder out = new StringBuilder();
      for (RaidBossRow row : RAIDBOSS_HELP_ROWS) {
         if (!out.isEmpty()) {
            out.append('\n');
         }
         out.append("&7  &e").append(String.format("%-12s", row.key())).append("&7").append(row.text());
      }
      return out.toString();
   }

   private static int raidbossHelp(CommandContext<CommandSourceStack> ctx) {
      if (((CommandSourceStack)ctx.getSource()).getPlayer() != null) {
         RaidBossHelpMenu.open(((CommandSourceStack)ctx.getSource()).getPlayer());
         return 1;
      } else {
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(
               () -> Component.literal(
                  Chat.colorize(
                     "&6&lRaid Bosses&r&7 - run &e/raidboss help <boss>&7 to see its summon recipe\n"
                        + raidBossHelpList()
                  )
               ),
               false
            );
         return 1;
      }
   }

   private static int raidbossActive(CommandContext<CommandSourceStack> ctx) {
      String text = BossManager.activeBossesText(((CommandSourceStack)ctx.getSource()).getServer());
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(Chat.colorize(text.isEmpty() ? "&7No raid bosses are active right now." : "&6&lActive raid bosses&r\n" + text)), false
         );
      return 1;
   }

   private static int raidbossRecipe(CommandContext<CommandSourceStack> ctx, String boss) {
      String key = boss.toLowerCase(Locale.ROOT);

      String pageKey = switch (key) {
         case "slime", "slimeking" -> "slime";
         case "golem", "stone", "stonegolem" -> "golem";
         case "mindbinder", "mind", "eye", "mindbinder_eye" -> "mindbinder";
         case "snow", "snowqueen", "icequeen", "snow_queen" -> "snow";
         case "timelord", "time_lord", "timelordtoken", "rift", "spacetime", "spacetime_rift", "space_time_rift" -> "timelord";
         case "scarlet", "scarletdevil", "scarlet_devil", "devil", "remilia", "scarletblood", "scarlet_blood" -> "scarlet";
         case "clockwork", "clockworkking", "clockwork_king", "cog" -> "clockwork";
         case "magister", "starbound", "starboundmagister" -> "magister";
         case "voidshaper", "void_shaper", "shaper", "colossus" -> "voidshaper";
         case "sovereign", "emeraldsovereign", "emerald_sovereign", "emeraldking" -> "sovereign";
         case "puppeteer", "puppet", "marionette", "strings", "emptymask", "empty_mask", "puppetmaster" -> "puppeteer";
         case "sculk", "elderwarden", "warden" -> "sculk";
         default -> "king";
      };
      if (((CommandSourceStack)ctx.getSource()).getPlayer() != null && !key.equals("unknown")) {
         if (!RAIDBOSS_KEYS.contains(key)) {
            ((CommandSourceStack)ctx.getSource())
               .sendSuccess(() -> Component.literal(Chat.colorize("&cUnknown boss '&f" + boss + "&c'. Try &e/raidboss help&c for the list.")), false);
            return 0;
         } else {
            RaidBossHelpMenu.open(((CommandSourceStack)ctx.getSource()).getPlayer(), pageKey);
            return 1;
         }
      } else {
         String page = switch (key) {
            case "king", "wither", "kingwither", "witherskeleton" -> "&6&lKing Wither Skeleton\n&7Summon: right-click a &fWithering Memory&7.\n&7Crafting recipe (3x3):\n&f[C][B][C]\n[B][W][B]\n[C][B][C]\n&7C = &fCoal&7, B = &fBone&7, W = &fWither Skeleton Skull\n&7Drops King Loot Boxes, Wither Essence and wither legendaries.";
            case "slime", "slimeking" -> "&a&lSlime King\n&7Summon: right-click a &fGelatinous Crown&7.\n&7Crafting recipe (3x3):\n&f[Sl][Sl][Sl]\n[Sl][H][Sl]\n[Sl][Sl][Sl]\n&7Sl = &aSlime Block&7, H = &6Golden Helmet\n&7Drops Slime King Loot Boxes, Mythical Gelatin and slime legendaries.";
            case "golem", "stone", "stonegolem" -> "&7&lStone Golem\n&7Summon: throw a &fBoulder Baby&7 - it shatters on\n&7impact and the golem rises from the rubble.\n&7Crafting recipe (3x3):\n&f[St][St][St]\n[St][I][St]\n[St][St][St]\n&7St = &7Stone Bricks&7, I = &7Iron Block\n&7It is nigh indestructible while armored - make it stagger itself!\n&7Drops Stone Golem Loot Boxes, Golem Cores and stone legendaries.";
            case "mindbinder", "mind", "eye", "mindbinder_eye" -> "&5&lMindbinder\n&7Summon: throw an &5Ominous Eye&7.\n&7Crafting recipe (3x3):\n&f[E][ ][E]\n[ ][P][ ]\n[E][ ][E]\n&7E = &5Eye of Ender&7, P = &aEnder Pearl\n&7A mid-game mind-war: don't meet its eye, break the\n&7QTEs by sneaking, and free your friends from the void.\n&7Drops the Staff, Possessed Mask and Shattered Mind.";
            case "snow", "snowqueen", "icequeen", "snow_queen" -> "&b&lSnow Queen\n&7Summon: right-click a &bCryogenic Core&7.\n&7Crafting recipe (3x3):\n&f[Sn][Sn][Sn]\n[Sn][D][Sn]\n[Sn][Sn][Sn]\n&7Sn = &fSnow Block&7, D = &bDiamond\n&7She freezes you into ice cubes and drags you into\n&7her frozen realm - die there and your gear waits\n&7in an ice tomb. Bring a pickaxe.\n&7Drops Frozen Hearts, the Ice Staff, Frostbound\n&7Bow and Glacier Cloak (Snow Queen Loot Boxes).";
            case "timelord", "time_lord", "timelordtoken", "rift", "spacetime", "spacetime_rift", "space_time_rift" -> "&d&lThe Time Lord\n&7Summon: right-click a &dSpace-Time Rift&7.\n&7Crafting recipe (3x3):\n&f[E][C][E]\n[C][A][C]\n[E][C][E]\n&7E = &dEcho Shard&7, C = &fClock&7, A = &5Amethyst Shard\n&7He stops time - and while time is stopped you\n&7cannot move or attack. Time it against his\n&7rifts and arrow halos, then punish the second\n&7he lets go.\n&7Drops his Loot Box (Pocket-Watch, Chrono Shard,\n&7Hourglass of Haste), Echo Shards, Amethyst,\n&7Diamonds, Netherite Scrap and Aging tomes.";
         case "puppeteer", "puppet", "marionette", "strings", "emptymask", "empty_mask", "puppetmaster" -> "&5&lThe Puppeteer\n&7Summon: right-click a &5Wooden Marionette&7.\n&7Crafting recipe (3x3):\n&f[T][T][T]\n[T][H][T]\n[T][T][T]\n&7T = &fString&7, H = &6Carved Pumpkin\n&7He ties strings to players. A string pulls,\n&7it never holds, and &flanding a hit on him\n&7cuts it. Run far enough and it snaps.\n&7A &ethreaded&7 player who dies leaves a\n&7&5Puppet&7 of themselves behind, fighting\n&7their own team.\n&7Drops his Loot Box (Puppeteer's Mask,\n&7Marionette Strings, The Empty Mask).";
            case "sculk", "elderwarden", "warden" -> "&8&lElder Warden\n&7Summon: right-click a &8Sculk Medallion&7.\n&7Crafting recipe (3x3):\n&f[Ec][Gb][Ec]\n[Gb][Ss][Gb]\n[Ec][Gb][Ec]\n&7Ec = &8Echo Shard&7, Gb = &6Gold Block&7, Ss = &8Sculk Sensor\n&7It hunts by sound in the deep dark - and it has a\n&7sonic boom of its own. Drops Sculk Loot Boxes and\n&7Sculk Essence.";
            case "scarlet", "scarletdevil", "scarlet_devil", "devil", "remilia", "scarletblood", "scarlet_blood" -> "&4&lScarlet Devil\n&7Summon: right-click a &4Scarlet-Blood&7.\n&7Crafting recipe (3x3):\n&f[E][R][E]\n[R][B][R]\n[E][R][E]\n&7E = &6Ghast Tear&7, R = &cRedstone Block&7, B = &fGlass Bottle\n&7She drinks your blood to heal, and when you die\n&7her bone servant wears your gear. Phase two\n&7brings the blood rain. Drops her Loot Box,\n&7Trophy, Bloodsoaked Cores and her three\n&7legendary weapons: Fang, Grimoire, Prism.";
            case "clockwork", "clockworkking", "clockwork_king", "cog" -> "&6&lClockwork King\n&7Summon: right-click a &6Clockwork Core&7.\n&7Crafting recipe (3x3):\n&f[I][R][I]\n[R][C][R]\n[I][R][I]\n&7I = &fIron Block&7, R = &cRedstone Block&7, C = &fClock\n&7His armour IS his machinery: every turret, blade,\n&7piston and drone is a tier of Resistance, and\n&7torn-off parts each chip his real health.\n&7Drops his Loot Box, Trophy, Mech-Scrap and his\n&7three legendaries: Gauntlet, Heart, Automaton Armor.";
            case "magister", "starbound", "starboundmagister" -> "&b&lStarbound Magister\n&7Summon: right-click an &bAstral Compass&7.\n&7Crafting recipe (3x3):\n&f[A][L][A]\n[L][C][L]\n[A][L][A]\n&7A = &bAmethyst Shard&7, L = &9Lapis Lazuli&7, C = &7Compass\n&7No arena: she keeps her distance and blinks when\n&7you close. From 60% she banks Astral Energy -\n&7six of them overloads in your face, so silence\n&7her early. At 25% the sky starts falling.\n&7Drops her Loot Box, Trophy, Magical Essence and\n&7Starpiercer, Astral Mantle and Magister's Codex.";
            case "voidshaper", "void_shaper", "shaper", "colossus" -> "&5&lVoid Shaper\n&7Summon: right-click a &5Void Anchor&7.\n&7Crafting recipe (3x3):\n&f[O][E][O]\n[E][P][E]\n[O][E][O]\n&7O = &8Obsidian&7, E = &5Ender Pearl&7, P = &5Eye of Ender\n&7He does not shoot fireballs: he rips blocks out of\n&7the world and throws them. The block decides the\n&7blow - ore flies fast, logs throw you far, magma\n&7burns, leaves shatter. At 40% he stops aiming.\n&7Drops his Loot Box, Trophy, Voidsteel Scrap and\n&7Void Reaver, Colossus Plate and Shaping Sigil.";
            case "sovereign", "emeraldsovereign", "emerald_sovereign", "emeraldking" -> "&a&lEmerald Sovereign\n&7Summon: right-click a &aSovereign's Crown&7.\n&7Crafting recipe (3x3):\n&f[G][E][G]\n[E][G][E]\n[G][E][G]\n&7G = &6Gold Block&7, E = &aEmerald Block\n&7He fights with subjects: a bell that staggers you\n&7and Royal Guards who fight for him. His court is\n&7his armour - clear it to open him up. He never\n&7takes anything from your inventory.\n&7Drops his Loot Box, Trophy, Royal Tribute and\n&7Royal Contract, Sovereign's Bell, Emerald Seal.";
            default -> null;
         };
         if (page == null) {
            ((CommandSourceStack)ctx.getSource())
               .sendSuccess(() -> Component.literal(Chat.colorize("&cUnknown boss '&f" + boss + "&c'. Try &e/raidboss help&c for the list.")), false);
            return 0;
         }
         ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal(Chat.colorize(page)), false);
         return 1;
      }
   }

   private static int ffGiveList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p != null) {
         ItemListMenu.open(p);
         return 1;
      }

      StringBuilder sb = new StringBuilder();
      ItemStack[] items = allModItems(((CommandSourceStack)ctx.getSource()).getServer().registryAccess());

      for (int i = 0; i < items.length; i++) {
         if (sb.length() > 0) {
            sb.append("&7\n");
         }

         sb.append("&f").append(aliasFor(items[i])).append("&7 → ").append(items[i].getHoverName().getString());
      }

      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
               Chat.colorize(
                  "&a&lFortune & Favors items&r\n&7Give: &f/ff give <item> [player]&7 · everything: &f/ff give all\n&7You can type the alias or the full name (e.g. &f/ff give King Loot Box&7):\n"
                     + sb
               )
            ),
            false
         );
      return 1;
   }

   private static int ffHelpOverview(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack src = (CommandSourceStack)ctx.getSource();
      boolean op = src.permissions() != null && src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
      StringBuilder sb = new StringBuilder();
      sb.append("&6&lFortune & Favors wiki&r&7 - run &e/ff help <topic>\n");
      sb.append("&a&lFOR EVERYONE\n");
      sb.append("&7  &emenu&7        everything in one GUI\n");
      sb.append("&7  &eeconomy&7     money, selling, shops, auctions, tokens\n");
      sb.append("&7  &ejobs&7        job board, bounties, trading, deliveries\n");
      sb.append("&7  &eskills&7      Mining / Combat / Farming / Trading levels\n");
      sb.append("&7  &ebosses&7      raid bosses, boss codex, loot\n");
      sb.append("&7  &eguilds&7      guilds, wars, the guild mine (/mine)\n");
      sb.append("&7  &estreaks&7     daily login streak & rewards, titles\n");
      sb.append("&7  &echallenges&7  daily & weekly challenges\n");
      sb.append("&7  &erecords&7     first-ever records, tags, newspaper\n");
      sb.append("&7  &escoreboard&7  your own sidebar - lines, order, title (/ff scoreboard)\n");
      sb.append("&7  &eduels&7       duel modes, stats, arenas\n");         sb.append("&7  &emystery&7     mystery chests & keys\n");
         sb.append("&7  &elottery&7     weekly jackpot - buy tickets with /lottery\n");         sb.append("&7  &ebank&7        safe cash vault (interest) + XP vault\n");
      sb.append("&7  &estocks&7      the Exchange - shares in 8 listings, charts, buy/sell (/stocks)\n");
      sb.append("&7  &edisasters&7   blood moons, gold rush, thunderstorms & more\n");
      sb.append("&7  &eraremobs&7    armored / cursed / elite / mythic mobs\n");
      sb.append("&7  &eforge&7       item forge, tiers, legendaries\n");
      sb.append("&7  &eshop&7        shop categories + exclusive machines\n");
      sb.append("&7  &eclaims&7      chunk claims, /claim loot, permissions\n");
      sb.append("&7  &emachines&7    auto-sell hopper, elevator, redeemer, infuser\n");
      sb.append("&7  &etokens&7      tokens and redemption\n");
      if (op) {
         sb.append("&c&lFOR ADMINS (OP)\n");
         sb.append("&7  &econfig&7      feature switches & server settings\n");
         sb.append("&7  &eadmin&7       /economy, /ff give, backups, recovery\n");
         sb.append("&7  &eworld&7       map editor, explosion rebuild, nice keep inv\n");
         sb.append("&7  &evanish&7      hide yourself completely - tab, body, gear, chat\n");
         sb.append("&7  &eperf&7       per-system tick cost + live entity & particle counts\n");
      } else {
         sb.append("&c&lFOR ADMINS (OP)\n&8  hidden - admin topics show up if you have OP.");
      }
      src.sendSuccess(() -> Component.literal(Chat.colorize(sb.toString())), false);
      return 1;
   }

   private static int ffHelpTopic(CommandContext<CommandSourceStack> ctx, String topic) {
      String page = switch (topic.toLowerCase()) {
         case "economy", "money" -> "&6&lEconomy\n&7Everything you break, mine or craft has a sell price.\n&e/sell&7  sell your whole inventory &7(&e/sell hand&7 / &e/sell all&7)\n&e/buy <item> <n>&7  buy straight from the catalog\n&e/shop [category]&7  browse the shop GUI\n&e/balance&7 (&e/money&7) shows cash, gems and pending items\n&7Chest shops: place a buy/sell sign on a chest and set prices\n&7with &e/chestshop price&7 - visitors trade with the chest.\n&7Auctions (&e/auction&7), &5gems&7, favor tokens (&e/token&7) and jobs\n&7(&e/jobs&7) round out the economy. Selling scales with\n&7your &eTrading&7 skill - see &e/ff help skills&7.";
         case "skills", "skill" -> "&6&lSkills\n&7Six skills level from what you do: &eMining&7 (mine blocks),\n&eCombat&7 (kill mobs), &eFarming&7 (grow/harvest),\n&eTrading&7 (sell stuff), &eForaging&7 (chop wood) and\n&eEnchanting&7 (forge & enchant). Every level-up lets you pick\n&7an upgrade: haste, fortune, autosmelt, damage, lifesteal,\n&7tank, growth, replant, commission, barter and more.\n&7Each tree caps at level &e10&7 - the tenth level pays a\n&7&6capstone point&7 and ends that skill, so a finished tree\n&7stops taking XP and stops paying. Later levels ask for\n&7more than early ones. Finish all six for &6Master of All&7.\n&7Open &e/skills&7 to spend points - see &e/ff help economy&7\n&7for the XP sources.";
         case "forge", "itemforge" -> "&6&lItem Forge\n&7Buy the &eItem Forge&7 (exclusive shop) and place it - a\n&7smithing table you right-click. Three slots: the legendary\n&7item, its boss material, and an optional enchantment tome.\n&7&5Wither Essence&7 forges wither gear, &dMythical Gelatin&7 slime gear.\n&7Tier II costs 1 material, Tier III costs 2 more. Forging\n&7buffs damage/charges/cooldowns; ENCHANT fuses Life Steal\n&7or Sticky (I-III). Max tiers: Launcher instant 5-ball, Shield\n&76 blocks, Blade +12 dmg, Crown always-Regen I, Staff twin\n&7skeletons. Old boss drops right-click the same window.";
         case "shop", "shops" -> "&6&lShops\n&7&e/shop&7 opens the catalog - categories &eBuilding&7, &eFood&7,\n&7&eTools&7, &eRedstone&7 and the &6Exclusive&7 page (machines).\n&7Chest shops: hold a &ebuy sign&7 or &esell sign&7 and click a\n&7chest to create the shop, then &e/chestshop price <n>\n&7set the price. Buy signs restock from the owner's balance\n&7when needed. Sell signs pay out instantly. A shop's owner\n&7configures currency and prices by opening the chest.\n&7The wormhole potion (exclusive) teleports between players\n&7who accept the request, or to up to 3 personal waypoints.\n&7See &e/ff help wormhole&7.";          case "claims", "claim" -> "&6&lClaims\n&e/claim&7 (or &e/claim menu&7) opens your Claims & Land\n&7GUI: claim list with one-click permission management,\n&7a claimer button, claim loot vault, slot buying and\n&7border mode - all in one screen. &e/claim give&7 hands you\n&7a Chunk Claimer directly: right-click the ground to\n&7claim that whole chunk. Sneak-right-click a chest\n&7inside a claim to open that claim's chest-shop menu.\n&7Boss drops and lost gear land in your claim vault\n&7(&e/claim loot&7). If a mod update ever wipes ownership,\n&e/claim recover&7 opens a preview-and-confirm screen listing\n&7every claim that can come back from the save file and its\n&7backups - nothing is merged until you press Confirm.\n&e/claim recover force&7 runs it immediately (console/scripts).\n&7The same screen backs &e/spawners recover&7 and\n&e/ff restore spawners&7 | &e/ff restore machines&7.";
         case "machines", "machine" -> "&6&lMachines\n&7All machines are vanilla blocks bought in the exclusive shop.\n&7&eAuto-Sell Hopper&7 - sells whatever it picks up, feeds to\n&7your balance (right-click for its effects toggle).\n&7&eElevator&7 - iron block that hops floors on use.\n&7&eToken Redeemer&7 - gold block that pays out tokens\n&7(owner funds the pool, configures payouts).\n&7&eSpawner Infuser&7 - crafting table that upgrades a spawner\n&7into a personal spawner (right-click to manage).\n&7&eItem Forge&7 - smithing table that upgrades boss gear.\n&7&eItem Sorter&7 - a hopper that keeps only the items you\n&7tell it to. Right-click it and shift-click a pack item to\n&7add or drop it. The top row sets what it reads from\n&7(hoppers / any container) and where everything else goes\n&7(where it points / the nearest container / one you pin);\n&7click an item in the list and then a container in the\n&7bottom row to send just that item there. An empty list\n&7moves nothing, and a sorter never pulls out of a sorter.\n&7Tag a chest with a &eSorter Tag&7 (rename it in an anvil to\n&7name the tag) and click that tag in the sorter's last row\n&7to pull out of it from up to 32 blocks away - the chest\n&7has to be in a loaded chunk for that to find anything.\n&7A plain sign is just a sign; only the Sorter Tag tags.\n&7&eChunk Anchor&7 - lodestone that keeps a 3x3 of chunks loaded.\n&7&eRepair Station&7 - grindstone that mends gear for cash.\n&7&ePortable Furnace&7 / &ePortable Campfire&7 - a blast furnace\n&7and a smoker you can carry, contents and all.\n&7&eAuto Planter&7 / &eAuto Harvester&7 / &eSprinkler&7 - the farm.\n&7The heavy hoppers: &eSuper Hopper&7 moves a stack a\n&7tick, and so do the &eItem Sorter&7 and both transfer\n&7hoppers now - they move up to a stack a pulse where a\n&7plain hopper trickles. &eTransfer Hopper&7 moves\n&7everything and links to tagged containers in and out. Clicking a tag in its\n&7window pulls from that container; &fshift-clicking&7 one\n&7makes it the &foutput&7 instead - everything the hopper\n&7holds goes there and is never read back out, so a tagged\n&7chest is a one-way pipe even when it stands right beside\n&7the hopper. Tag a second Transfer Hopper and point the\n&7output at it to chain them. &eOverflow Hopper&7 is that\n&7same machine with the tag moved to the end - it fills\n&7what it points at first and only spills what will not\n&7fit into the tag, so naming a second chest there keeps\n&7the first one full instead of stalling the farm. Its\n&7window says whether the chest in front of it is full.\n&eItem Checker\n&7Hopper&7 takes only what no sorter wants (facing another\n&7one it hands the stack over instantly - chain to a void;\n&7right-click it with an empty hand to switch it to voiding,\n&7so one hopper in a corner is a bin); &e2-Way Splitter&7\n&7divides its input evenly between the two containers\n&7beside it. &eSuper Smelter&7 cooks anything faster than a\n&7blast furnace and drinks fuel.\n&7&eTiers&7: right-click a &eSuper Hopper&7 (empty hand), or\n&7sneak-right-click a &eSuper Smelter&7, to buy tiers with\n&7cash - each one faster and thirstier than the last. A\n&7tier is carried on the item, so moving the machine keeps\n&7it.\n&7&eMachine particles&7: &e/ff config machine_particles&7 turns\n&7the machines' own sparkle off. At most five appear a tick\n&7across the server, and no other particle is affected.\n&7Sneak-right-click any machine to pick it back up.";
         case "jobs", "job" -> "&6&lJobs & Bounties\n&7&e/jobs&7 opens your job board - pick jobs that pay out\n&7cash as you work (mining, farming, killing, trading).\n&7&e/bounty <player> <amount>&7 puts a hit out on someone;\n&7their killer gets the cash. &e/bounty list&7 shows open\n&7bounties, &e/bounty cancel&7 pulls yours back.\n&7&e/trade <player>&7 opens a trade window with them,\n&7&e/tradeaccept&7 confirms. &e/wormhole&7 lets a friend\n&7teleport to you if you accept.";
         case "tokens", "token" -> "&6&lFavor Tokens\n&7Favor tokens are minted by their creator (shift-right-click\n&7to brand) and come in versions I-IV. A &eToken Redeemer&7\n&7(gold block) pays them out. &5Gems&7 are a separate currency\n&7earned from raids, challenges, contracts and achievements.\n&7Spend gems at the &5/gemshop&7 for exclusive items.\n&7&e/token&7 opens your favor token inventory.";
         case "guilds", "guild", "war", "wars" -> "&6&lGuilds\n&7&e/guild&7 opens the guild GUI - found or join a crew.\n&7&e/guild create <name>&7, &e/guild invite <player>&7,\n&7&e/guild motd <text>&7, &e/guild leave&7, &e/guild disband&7.\n&7Guilds score &cPvP&7 (kills), &aWealth&7 (money) and &bPvE&7 (bosses)\n&7and level up for &eGuild Skills&7 perks (Wisdom = +XP,\n&7Fortune = +cash). Founders can toggle &eFriendly Fire&7\n&7and declare &cwar&7 on another guild (&e/guild declarewar&7,\n&7&e/guild peace&7) - kills count double while at war.\n&7The &eGuild Mine&7 (&e/mine&7) auto-produces cash for the\n&7founder; build it, upgrade it, and deposit to speed payouts.";
         case "streaks", "streak", "rewards", "daily", "titles", "title" -> "&6&lDaily Streak & Rewards\n&7Log in every day to grow your streak - miss one day and a\n&7&eStreak Freeze&7 saves it (earn one every 5 days, max 3).\n&7Day 1: &a$100&7 · Day 3: &a$500&7 · Day 7: &a$5k + Common Key&7\n&7· Day 14: &a$15k + [Veteran] tag&7 · Day 30: &a$50k + [Dedicated] title.\n&7Claim today's reward in &e/menu → Daily Streak&7 or &e/rewards&7.\n&7Other streaks: &e10/25/50&7 duel wins → Duelist/Swordsman/Champion,\n&7&e4&7 bosses without dying → Boss Slayer, &e20&7 bounty levels →\n&7Bounty Hunter. Equip titles with &e/ff title <name>&7.";
         case "challenges", "challenge" -> "&6&lDaily & Weekly Challenges\n&7Rotating goals that pay cash + a &5token&7 when done.\n&7Daily challenges reset at midnight EST, weekly on Monday.\n&7Mine stone, kill mobs or bosses, win duels, fulfil contracts,\n&7complete bounties, trade, sell - there's always something.\n&7Track progress in &e/menu → Challenges&7 or &e/ff challenges&7.";
         case "codex", "bosses", "boss" -> "&6&lBosses & Codex\n&7&lKing Wither Skeleton&r&7 - the wither-gear boss. Fights in\n&7phases: soul tether, skeleton hordes, fireball charges,\n&7a blood ritual that drains one player (strike it to break)\n&7and the devour - it eats its Guards for a shield only\n&7fully-charged hits can break. Drops loot boxes, King's\n&7Bones, and wither legendaries. Slain victims rise as a\n&7&cBlood Revenant&7 wearing their gear - slay it to reclaim.\n&7&lSlime King&r&7 - the slime-gear boss. Bounces, splatters\n&7goo, shockwave rings, and swallows players (spam Shift to\n&7escape). Drops loot boxes, Mythical Gelatin and slime gear.\n&7&lStone Golem&r&7 - the stone-gear boss. Low health but nigh-\n&7indestructible armor: player hits deal 1 damage while armored.\n&7Its own moves stagger it - Stone Slam cracks its armor open,\n&7wall-charges stun it, and boulders/debris bounce back to hit\n&7it. Phase 2 enrages. Drops Golem Cores and the staff/fist/\n&7stoneheart legendaries. There are also &lMindbinder&7, &lSnow\n&7Queen&7 and &lElder Warden&7 fights. The &dBoss Codex&7 in\n&7/menu logs every kill, firsts, and fastest kills.\n&7The &5Puppeteer&7 - &e/ff test puppet 1-3&7 puts that many of his\n&7strings on you on demand: one and two are pulls, and three runs\n&7the real windup first, so you can watch the bar, then break it by\n&7running or hitting him.";
         case "records", "record", "firsts" -> "&6&lRecords & Tags\n&7First-ever records are permanent history: first boss kill,\n&7first millionaire, first mythic spawn and more - see them in\n&7&e/menu → Records&7 or &e/tags&7. Your &b[tag]&7 is personal flair\n&7in chat and tab: &e/tag set <text>&7, &e/tag color <r> <g> <b>&7,\n&7&e/tag clear&7. Titles come from streaks - &e/ff title list&7,\n&7&e/ff title <name>&7 to equip.";
         case "news", "newspaper", "paper" -> "&6&lServer Newspaper\n&7A fresh edition drops at midnight EST - top stories, market\n&7reports, the bounty board and discoveries, with real data\n&7mixed into 50+ headline templates. Read it in &e/menu → Server\n&7News&7 or &e/news&7. Need the latest breaking story? It's always\n&7a fresh paper.";
         case "deliver", "deliveries", "contracts", "contract" -> "&6&lDynamic Contracts\n&7The market wants things: urgent contracts spawn when players\n&7fall to a boss, and daily shortages ask for specific items.\n&7Open &e/deliver&7 (or &e/menu → Deliveries&7), drop matching\n&7items in the hopper and hit &aDELIVER!&7 - cash lands instantly.\n&7Contracts expire after 24h, so keep an eye on the board.";
         case "mystery", "mysterychest", "mysterychests", "keys" -> "&6&lMystery Chests\n&7Four tiers of chests: &eCommon&7, &dRare&7, &5Epic&7 and &6Legendary&7.\n&7Keys come from streaks, challenges and rare drops - match\n&7the key tier to the chest tier in &e/menu → Mystery Chests&7.\n&7Higher tiers pay out bigger cash and better items.";
         case "lottery", "jackpot" -> "&6&lWeekly Lottery\n&7&e/lottery&7 (or &e/menu → Lottery&7) opens the draw window - buy\n&7tickets for &a$100&7 each (max 100 a week). Every &eMonday at\n&7midnight EST&7 one ticket is drawn: the winner takes &675%&7 of\n&7the pot, &825%&7 rolls over so the jackpot keeps growing.\n&7The more players buy in, the bigger the pot gets: on top of\n&7the ticket money the house pays a &bcrowd bonus&7 that scales\n&7with both the number of tickets sold and the number of\n&7&7different players holding them. If nobody buys in, the whole\n&7pot carries over. The winner is announced in chat and in the\n&7next paper.";
         case "stocks", "stock", "exchange", "shares", "market" -> "&6&lThe Exchange\n&7&e/stocks&7 (or &e/menu → Stocks&7) opens the market that lives\n&7inside your bank: eight mining listings - Emerald Exchange,\n&7Redstone Foundry, Diamond Consortium, Quartz Optics, Ancient\n&7Debris Ltd., Gilded Carrot Farms, Ender Pearl Freight and Blaze\n&7Rod Energy. A new quote prints every &f5 real minutes&7, and the\n&7market is driven by the world clock rather than by who is\n&7logged in: it keeps moving while you are away, so the chart of\n&7the week you missed is the week that actually happened.\n&7· Click a listing for its chart - nine columns of price, four\n&7  rows tall, green when a quote is above the one before it.\n&7· The window button steps the chart through 45 minutes, three\n&7  hours, a day and a week.\n&7· Buying is funded from your wallet first and your vault second;\n&7  selling pays into your wallet and lands on your bank statement.\n&7· Brokerage is &f1%&7 each way, so a round trip must beat 2% to\n&7  pay for itself, and every price is bounded around its base.\n&7· Eight listings share one market mood, so a bad day is red all\n&7  down the board - and a share is never announced in chat. Go\n&7  and look, or don't: holding costs nothing and pays nothing.";
         case "bank", "banks", "interest", "xpbank", "vault" -> "&6&lThe Bank\n&7&e/bank&7 (or &e/menu → Bank&7) opens your bank: a &emoney\n&7vault&7 and an &bXP vault&7 that death and raids can never touch.\n&7· Deposits are capped by the capacity meter - upgrade it\n&7  with cash ($25k → $2.5M, max $100M).\n&7· Banked cash earns &adaily interest&7 (small, tapers as the\n&7  vault fills - it can never snowball). The &6interest cap&7\n&7  upgrade raises the max payout per day.\n&7· The XP vault stores raw points, lossless in and out -\n&7  upgrade it with XP levels (725 → 27250 XP).";
         case "duels", "duel" -> "&6&lDuels\n&7Challenge a player with &e/duel <player>&7 and pick a mode -\n&7classic, UHC, legacy 1.8 combat, skywars kits, draft, bedwars\n&7and more. Win streaks earn titles (10 Duelist, 25 Swordsman,\n&750 Champion). Your record and leaderboards live in\n&7&e/menu → Duel Records&7.";          case "disasters", "disaster" -> "&6&lServer Disasters\n&7Every so often the world transforms: &cBlood Moon&7 (mobs hit harder),\n&6Mining Collapse&7 (2x ores), &eMerchant Festival&7 (+25% sell), &4Monster\n&7Invasion (mobs attack), &6Gold Rush&7 (2x ore drops), &bThunderstorm&7\n&7(lightning + charged creepers), &5Double Trouble&7 (2x mobs & damage),\n&aXP Frenzy&7 (2x skill XP) and &8Phantom Swarm&7 (phantoms descend).\n&7They last 10 min and roll on their own roughly every 20 minutes.\n&7See &e/ff event&7 to list, trigger, or stop them.";
         case "raremobs", "raremob", "variants" -> "&6&lRare Mob Variants\n&7Mobs can spawn as &7Normal&7, &bArmored&7, &cCursed&7, &5Elite&7,\n&7&3Ancient&7 or &dMythic&7 - rarer tiers are stronger, marked by a\n&7subtle particle aura, and drop better loot. They hide until they\n&7notice a player, then drop the act. The first Mythic ever spawned\n&7is recorded in the hall of firsts forever.";
         case "scoreboard", "sidebar" -> "&6&lYour Scoreboard\n&7&e/ff scoreboard&7 shows it and leaves it on, and\n&e/ff scoreboard disable&7 takes it away - &e/stats scoreboard&7 is\n&7the same command. The board carries ten lines - the &7address, your\n&7tag, your guild, gems, money, the online count, your skill levels,\n&7your playtime, the job you are closest to finishing, and a line you\n&7write yourself - and each one is yours to switch: &e/ff scoreboard\n&7lines&7 lists them in their order, &e/ff scoreboard on|off|toggle\n&7<line>&7 flips one, and &e/ff scoreboard move up|down <line>&7 reorders\n&7them. The three written texts are editable as well: &e/ff scoreboard\n&7title <text>&7, &e/ff scoreboard ip <text>&7 and &e/ff scoreboard\n&7custom <text>&7 take colour codes like every other string in this mod,\n&7and &e/ff scoreboard banner title|ip&7 decides which of the two board\n&7texts sits on top. &e/ff scoreboard edit&7 opens the same thing as a\n&7screen, with every switch and its live text on it. Nothing here is an\n&7admin setting - a board is a thing you look at, so it is all per player.";
         case "tags", "tag" -> "&6&lTags & Titles\n&7&e/tags&7 opens the equip menu: click any title you've\n&7unlocked to wear it in chat, or any owned tag to equip\n&7it. The &eCreator of Mod&7 title stays on until you\n&7unequip it first. Your &b[tag]&7 also shows next to your\n&7name - set it with &e/tag set <text>&7, recolor with\n&7&e/tag color <r> <g> <b>&7, remove with &e/tag clear&7.";
         case "changelog", "log" -> "&6&lChangelog\n&7Run &e/changelog&7 to see the latest update notes in chat.\n&7The full history lives in the repo's CHANGELOG.md and is\n&7bundled with the mod jar.";
         case "menu", "hub" -> "&6&lMenu Hub\n&7&e/menu&7 opens one GUI with everything: shop, sell, auction,\n&7jobs, tokens, skills, claims, raid bosses, duels, bounty,\n&7guild, deliveries, mystery chests, server news, recipes,\n&7daily streak, boss codex, challenges, first-ever records,\n&7tags & titles, trading, expeditions, cosmetics, the\n&7leaderboard, the &6lottery&7, the bank and gems. Every window\n&7also has a command if you prefer typing - including\n&7&e/lottery&7, &e/bank&7, &e/token&7 and &e/raidboss&7.";
         case "config", "settings", "features" -> "&6&lAdmin: Config (OP)\n&7&e/ff config&7 (or &e/fortuneandfavors&7) opens the feature\n&7switches GUI: shop, auction, trade, token, claims, tags,\n&7elevator, autosell, jobs, bounty, skills, boss, duels,\n&7nice keep inventory and sculk abilities can be toggled per\n&7server. &e/ff config <feature>&7 toggles one from chat, and\n&7settings sync to every player's config screen automatically.\n&7Also set auction minutes, buy-now, boss minions/despawn,\n&7explosion rebuild, backups and keep-inventory claim rules.";
         case "admin", "staff", "eco" -> "&6&lAdmin: Economy & Staff (OP)\n&7&e/economy give|take|set|balance|reset <player> <amt>&7 -\n&7manage balances; &e/economy top&7 shows the rich list.\n&7&e/ff give <item>&7 hands out mod items (list with &e/ff give list&7).\n&7&e/ff backup&7 snapshots the world. &e/ff restore claims|spawners|machines&7\n&7(preview + Confirm GUI), &e/claim recover&7 and &e/spawners recover&7\n&7restore ownership and machines after a wipe. Add &econfirm&7 to any\n&7restore path to run it without the GUI.\n&7&e/economy boss spawn <mob> [hp]&7 spawns raid bosses,\n&7&e/economy price&7 sets sell multipliers or item overrides,\n&7&e/chestshop price|currency&7 manages chest shop pricing.\n&7&e/perms give|remove <player>&7 grants economy admin.";
         case "anticheat", "ac", "hackers", "cheaters" -> "&6&lAdmin: Anticheat (OP)\n&7&e/ff anticheat&7 reports the state. It is &cOFF&7 - always - and\n&7&coperators are exempt&7 by default, because staff cannot build\n&7with a movement check shoving them around.\n&7&e/ff anticheat on|off&7 switches the whole system.\n&7&e/ff anticheat op on|off&7 includes or exempts operators - the way\n&7to test it on yourself.\n&7&e/ff anticheat list&7 shows who has been flagged since the restart.\n&7Checks: speed, flight, reach, killaura, scaffold, fast-mine,\n&7knockback delay, ore vision (x-ray), no-fall, block reach,\n&7click timing, hit precision, and the packet layer - floods,\n&7positions no client can produce, NaN coordinates, forged hotbar\n&7slots and a client ticking faster than the server. The packet\n&7layer reads what the server sends too, so a teleport or a shove\n&7it issued is never read as cheating. Impossible hits, breaks and\n&7placements are refused, movement is set back, and alerts go to\n&7staff only. Nothing is ever automatic-punished by default.\n&7&e/ff feature anticheat_autoclicker&7 and &e/ff feature anticheat_aim\n&7silence either pattern check on its own. &e/ff anticheat compat\n&7lists the client-mod compatibility modes - Accurate Block Placement,\n&7Inventory Walk, Mouse Tweaks - and exactly what each one may\n&7and may not relax. None of them switches a check off.\n&e/ff anticheat tune&7 lists the enforcement dials (alert and enforce\n&7levels, decay, cooldowns) and &etune set <key> <value>&7 moves one -\n&7clamped to a safe range, saved to disk, and live immediately.\n&8Physics (reach margins, the speed model, placement geometry) is\n&8deliberately NOT listed and not settable: it decides whether a\n&8player is cheating, and a typo there is a false ban.";
         case "world", "mapedit", "explosions", "explosion" -> "&6&lAdmin: World (OP)\n&7&e/mapedit <mode>&7 toggles editing modes (list with &e/mapedit&7,\n&7restore with &e/mapedit restore&7). &e/explosionrebuild&7 reports\n&7its state (and how many blocks are queued); &e/explosionrebuild on|off&7\n&7rebuilds terrain after TNT, creepers and boss blasts. &e/NiceKeepInventory on|off&7\n&7controls the keep-inventory system, &e/NiceKeepInventory\n&7claimother&7 lets other players claim your drops. &e/ff test&7\n&7summons test bosses, &e/ff leaverealm&7 escapes the snow\n&7realm, &e/ff title&7 manages titles, &e/ff records&7 shows\n&7the hall of firsts.";
         case "perf", "performance", "tps", "lag", "profiler" -> "&6&lPerf Report (OP)\n&7&e/ff perf report&7 answers \"why is the server slow\" in three\n&7parts, in this order:\n&7· the server's own &fMSPT&7 and &fTPS&7, so the numbers that\n&7  follow have a denominator, and how much of the tick\n&7  Fortune & Favors is spending;\n&7· &flive&7 counts - entities and players per &fdimension&7, so a\n&7  leaking realm is named rather than added up, plus\n&7  &fparticles per tick&7 with the session peak;\n&7· every instrumented system's &favg&7 cost per tick and its\n&7  &fspike&7 - the worst single tick in the last five seconds.\n&7The middle part is the one that is easy to skip and the one\n&7that ends the argument: a hundred thousand entities and half a\n&7million particles a tick look identical in the cost table,\n&7and one of them is not a bug in this mod at all.\n&7&e/ff perf reset&7 drops every sample, so the next report\n&7measures only what has happened since - which is the\n&7difference between \"is this mod expensive\" and \"is the\n&7thing I just changed expensive\".\n&7&e/ff perf off&7 stops the profiler for zero overhead. It stops\n&7the particle counter with it, so turn it back on with\n&7&e/ff perf on&7 before reading the live half.";
         case "vanish", "stealth" -> "&6&lAdmin: Vanish (OP)\n&7&e/ff vanish&7 toggles stealth. While vanished you are removed\n&7from every other tab list, invisible, off the locator bar,\n&7silent, muted in chat, and your armour and held items are\n&7stashed and hidden from every client (including your own).\n&7&fNothing&7 is announced: no fake disconnect and no fake join,\n&7because a yellow line in chat is the loudest tell there is -\n&7observers see nothing at all.\n&7&e/ff vanish on&7 / &e/ff vanish off&7 set the state instead of\n&7toggling it, which is what a script or a habit needs.\n&7&e/ff vanish status&7 reports who is hidden, and for each body\n&7the diagnostics behind the state: how many times another\n&7system cleared a vanish flag (anything above two means\n&7something else on the server is fighting the vanish), the\n&7synced potion-particle count, and the emptied slots.\n&7You cannot talk while hidden - that is the cover.";
         case "wormhole", "wormhole_potion" -> "&6&lWormhole Potion\n&7Right-click it to open the portal window: warp to a\n&7player (they accept the request first), your respawn,\n&7last death, or a random surface spot - each teleport\n&7consumes the potion. You can also save up to &d3\n&7personal waypoints&7: click an empty slot to set one at\n&7your current spot (free), left-click a saved waypoint\n&7to warp there, right-click to remove it. Setting is\n&7free; warping consumes the potion. &e/wormhole accept\n&7| deny&7 answers incoming teleport requests.";
         default -> null;
      };
      if (page == null) {
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(() -> Component.literal(Chat.colorize("&cUnknown topic '&f" + topic + "&c'. &e/ff help&7 for the list.")), false);
         return 0;
      } else {
         String text = page;
         ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal(Chat.colorize(text)), false);
         return 1;
      }
   }

   private static int ffEventStart(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = com.fortuneandfavors.economy.ServerDisasterManager.startEvent(player.level().getServer(), name);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      }
      Chat.msg(player, "&aEvent started. Active: &f" + com.fortuneandfavors.economy.ServerDisasterManager.active());
      return 1;
   }

   private static int ffEventStop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (com.fortuneandfavors.economy.ServerDisasterManager.stopEvent(player.level().getServer())) {
         Chat.msg(player, "&aEvent stopped.");
      } else {
         Chat.msg(player, "&7No event is running right now.");
      }
      return 1;
   }

   private static int ffEventList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String active = com.fortuneandfavors.economy.ServerDisasterManager.active();
      Chat.raw(player, "§6§lWorld events" + (active.isEmpty() ? " §7- none active" : " §7- §4" + active + "§7 active!"));
      for (String e : com.fortuneandfavors.economy.ServerDisasterManager.eventNames()) {
         Chat.raw(player, " §f• §7" + e + (e.equals(active) ? " §a(active)" : ""));
      }
      Chat.raw(player, "§8Events roll automatically about every 20 minutes. Trigger one with /ff event <name>, stop with /ff event stop, or /ff event mythic for a mythic mob.");
      return 1;
   }

   private static int ffEventMythic(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      net.minecraft.world.entity.Mob mob = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(player.level(), net.minecraft.world.entity.EntitySpawnReason.EVENT);
      if (mob != null) {
         mob.setPos(player.getX() + 3.0, player.getY(), player.getZ());
         com.fortuneandfavors.economy.RareMobVariantManager.applyTier(mob, 5);
         mob.setPersistenceRequired();
         player.level().addFreshEntity(mob);
         Chat.msg(player, "&6A §6Mythic Zombie§6 has been summoned nearby - good luck!");
      } else {
         Chat.msg(player, "&cCouldn't spawn the mob.");
      }
      return 1;
   }

   /** Bare /ff now opens the OP command GUI (admins get every admin command in
    *  one window; non-admins are pointed at /menu and /ff help). */
   private static int ffOpMenu(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack src = (CommandSourceStack)ctx.getSource();
      if (src.getPlayer() instanceof ServerPlayer sp) {
         boolean op = sp.permissions() != null && sp.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
         if (op) {
            com.fortuneandfavors.menu.OpCommandMenu.open(sp);
         } else {
            Chat.msg(sp, "&cThat's the admin command hub - you need OP to use it. &e/menu&7 has everything for you.");
         }
      }
      return 1;
   }

   /** Toggles the Death Compass's grave particles for the caller. */
   private static int ffCompassFx(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack src = (CommandSourceStack)ctx.getSource();
      if (src.getPlayer() instanceof ServerPlayer sp) {
         boolean next = !com.fortuneandfavors.economy.DisplayPrefsManager.compassFx(sp.getUUID());
         ffCompassFxApply(sp, next);
      }
      return 1;
   }

   private static int ffCompassFxSet(CommandContext<CommandSourceStack> ctx, boolean on) {
      CommandSourceStack src = (CommandSourceStack)ctx.getSource();
      if (src.getPlayer() instanceof ServerPlayer sp) {
         ffCompassFxApply(sp, on);
      }
      return 1;
   }

   private static void ffCompassFxApply(ServerPlayer sp, boolean on) {
      com.fortuneandfavors.economy.DisplayPrefsManager.setCompassFx(sp.getUUID(), on);
      com.fortuneandfavors.economy.DisplayPrefsManager.save(sp.level().getServer());
      Chat.raw(sp, on ? "&aDeath Compass FX: &fON&7 - the grave tracking beam and beacon are shown." : "&aDeath Compass FX: &fOFF&7 - only the action-bar distance is shown, no particles.");
   }

   // TEMP dev-only handlers for the wither rework smoke test. Spawns a wither
   // near world spawn; the mod's claim sweep adopts it within 5s (or the spawn
   // mixin claims it if its chunk AI-ticks), then /wtest dmg drives the fight.
   private static int wtestSpawn(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = ctx.getSource();
      net.minecraft.server.level.ServerLevel level = source.getServer().overworld();
      // Fixed test spot near world origin, above ground level.
      net.minecraft.core.BlockPos sp = new net.minecraft.core.BlockPos(8, 96, 8);
      net.minecraft.world.entity.boss.wither.WitherBoss w = (net.minecraft.world.entity.boss.wither.WitherBoss)net.minecraft.world.entity.EntityTypes.WITHER
         .create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
      if (w == null) {
         source.sendFailure(Component.literal("Failed to create wither"));
         return 0;
      }
      w.setPos(sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5);
      w.setInvulnerableTicks(220);
      level.addFreshEntity(w);
      source.sendSuccess(() -> Component.literal("Spawned Ascended Wither at " + sp + " - claim within 5s, then use /wtest info"), true);
      return 1;
   }

   private static int wtestDmg(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = ctx.getSource();
      int amount = IntegerArgumentType.getInteger(ctx, "amount");

      for (net.minecraft.server.level.ServerLevel level : source.getServer().getAllLevels()) {
         for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (e instanceof net.minecraft.world.entity.boss.wither.WitherBoss w
               && com.fortuneandfavors.economy.WitherReworkManager.isFightLive(w.getUUID())) {
               w.hurt(level.damageSources().genericKill(), (float)amount);
               source.sendSuccess(() -> Component.literal("Damaged reworked wither by " + amount + " -> hp " + (int)w.getHealth() + "/" + (int)w.getMaxHealth()), true);
               return 1;
            }
         }
      }
      source.sendFailure(Component.literal("No live reworked wither found - run /wtest spawn first"));
      return 0;
   }

   private static int wtestInfo(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = ctx.getSource();
      StringBuilder sb = new StringBuilder();

      for (net.minecraft.server.level.ServerLevel level : source.getServer().getAllLevels()) {
         for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (e instanceof net.minecraft.world.entity.boss.wither.WitherBoss w) {
               sb.append("wither hp=").append((int)w.getHealth()).append('/').append((int)w.getMaxHealth())
                 .append(" invuln=").append(w.getInvulnerableTicks())
                 .append(" live=").append(com.fortuneandfavors.economy.WitherReworkManager.isFightLive(w.getUUID()))
                 .append(" name=").append(w.getCustomName() == null ? "none" : w.getCustomName().getString()).append(" | ");
            }
         }
      }
      source.sendSuccess(() -> Component.literal(sb.length() == 0 ? "no withers loaded" : sb.toString()), true);
      return 1;
   }

   /**
    * {@code /ff perf} - the per-system tick cost report, plus the world's live counts.
    *
    * <p>Bare and {@code /ff perf report} are the same thing on purpose: the command people type
    * from memory is the bare one, and "read a TPS regression off a command" is a thing somebody
    * does while the server is struggling, so neither spelling is allowed to be the wrong one.
    */
   private static int ffPerf(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      for (net.minecraft.network.chat.Component line : PerfMonitor.report(source.getServer())) {
         source.sendSystemMessage(line);
      }
      return 1;
   }

   /**
    * {@code /ff perf reset} - forget every sample collected so far.
    *
    * <p>The report's average is a mean over the whole session, which is the right number for "is
    * this mod expensive" and the wrong one for "is the thing I just changed expensive" - a
    * hundred thousand healthy ticks drown one bad system completely. Clearing the window is what
    * makes the second question answerable without a restart, and the baseline is only ever as far
    * behind as the last reset.
    */
   private static int ffPerfReset(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      PerfMonitor.clear();
      source.sendSystemMessage(net.minecraft.network.chat.Component.literal(
         Chat.colorize("&6&lFF Performance&r &7every sample has been dropped - the next report measures only what happens from here.")
      ));
      return 1;
   }

   /**
    * {@code /ff dragon} - the Ender Dragon rework, driven by hand.
    *
    * <p>Added because the rework has four pieces that a player cannot see on demand: the arrival
    * (once per server life), the breath lance (a move that has to be rolled), the last stand (a
    * health border), and the three phase rotations (a lot of damage). Staff get one command that
    * prints the whole machine and can push any of those borders, so "is the rework actually
    * running?" is a line of chat rather than a guess.
    */
   private static int ffDragonStatus(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      source.sendSystemMessage(net.minecraft.network.chat.Component.literal(
         Chat.colorize("&5&lFF Dragon&r &7" + com.fortuneandfavors.economy.EnderDragonManager.statusLine())
      ));
      source.sendSystemMessage(net.minecraft.network.chat.Component.literal(
         Chat.colorize("&7  /ff dragon rift | phase <1-3> | lance | finale | reset")
      ));
      return 1;
   }

   private static int ffDragonRift(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerLevel end =
         source.getServer().getLevel(net.minecraft.world.level.Level.END);
      ServerPlayer staff = source.getPlayer();
      boolean ok = end != null
         && com.fortuneandfavors.economy.EnderDragonManager.forceRift(end, staff == null ? null : staff);
      reply(source, ok, "the rift is tearing at the middle of the island", "there is no End level to tear a rift in");
      return ok ? 1 : 0;
   }

   private static int ffDragonPhase(CommandContext<CommandSourceStack> ctx, int phase) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerLevel end =
         source.getServer().getLevel(net.minecraft.world.level.Level.END);
      boolean ok = com.fortuneandfavors.economy.EnderDragonManager.forcePhase(end, phase);
      reply(
         source,
         ok,
         "the dragon's bar is in phase " + phase,
         "no dragon to move - run /ff dragon rift and walk to the middle first"
      );
      return ok ? 1 : 0;
   }

   private static int ffDragonLance(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerLevel end =
         source.getServer().getLevel(net.minecraft.world.level.Level.END);
      boolean ok = com.fortuneandfavors.economy.EnderDragonManager.forceBreathLance(end);
      reply(source, ok, "the breath lance is burning", "no dragon to breathe - the lance needs a body");
      return ok ? 1 : 0;
   }

   private static int ffDragonFinale(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      boolean ok = com.fortuneandfavors.economy.EnderDragonManager.forceFinale();
      reply(
         source,
         ok,
         "the last stand is running - eleven seconds, and then one heart",
         "no dragon, or one that has already had its last stand"
      );
      return ok ? 1 : 0;
   }

   private static int ffDragonReset(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      boolean ok = com.fortuneandfavors.economy.EnderDragonManager.resetFight();
      reply(
         source,
         ok,
         "the fight is back at the top - full health, phase one, and the arrival will play again",
         "no dragon to reset"
      );
      return ok ? 1 : 0;
   }

   /** One line either way, so a command that cannot act says why rather than doing nothing. */
   private static void reply(CommandSourceStack source, boolean ok, String yes, String no) {
      source.sendSystemMessage(net.minecraft.network.chat.Component.literal(
         Chat.colorize(ok ? "&5&lFF Dragon&r &a" + yes : "&5&lFF Dragon&r &c" + no)
      ));
   }

   private static int ffPerfToggle(CommandContext<CommandSourceStack> ctx, boolean on) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      PerfMonitor.setEnabled(on);
      source.sendSystemMessage(net.minecraft.network.chat.Component.literal(
         on
            ? Chat.colorize("&6&lFF Performance&r &aprofiler resumed - run /ff perf report for the report.")
            : Chat.colorize("&6&lFF Performance&r &7profiler stopped - zero overhead. Note that a stopped profiler also stops counting particles.")
      ));
      return 1;
   }

   /**
    * {@code /ff anticheat} - the state of the anticheat, and what it can see.
    *
    * <p>Reports the two switches first, because they are the answer to the only
    * question staff usually have, which is why nothing is being flagged: the whole
    * system is off until somebody turns it on, and operators are skipped until
    * somebody turns that off too.
    */
   /**
    * {@code /ff anticheat} - the panel, from a player; the state, from a console.
    *
    * <p>A person typing this is about to do something - look at who has been flagged, open a
    * case file, lift a punishment - and a page of text answers none of that. The account half
    * opens the records screen, which is the screen every moderation action already lives on, and
    * the status lines still go to chat underneath it so nothing that was readable is lost. A
    * console has no screen and gets the text, which is why both paths are here rather than one
    * replacing the other.
    */
   private static int ffAnticheatPanel(CommandContext<CommandSourceStack> ctx) {
      if (ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer viewer) {
         com.fortuneandfavors.menu.AntiCheatMenu.open(viewer, false);
         Chat.msg(
            viewer,
            "&7The records screen is open: every flagged player, with their case file a click away. "
               + "&f/ff anticheat banned&7 lists who is actually serving something, and &f/ff anticheat status&7 prints the state."
         );
      }
      return ffAnticheatStatus(ctx);
   }

   private static int ffAnticheatStatus(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      boolean on = com.fortuneandfavors.anticheat.AntiCheat.enabled();
      boolean ops = com.fortuneandfavors.anticheat.AntiCheat.checksOps();
      boolean opKick = ModConfig.anticheatOpKick();
      source.sendSuccess(() -> Component.literal(Chat.colorize("&c&l[AC]&r &fAnticheat: " + (on ? "&aON" : "&cOFF"))), false);
      source.sendSuccess(
         () -> Component.literal(Chat.colorize("&7Operators: " + (ops ? "&achecked" : "&aexempt &8(/ff anticheat op on to check them too)"))),
         false
      );
      // The other half of the operator answer, and the one that used to be silent: whether the
      // automatic half may act on staff. Either switch is now a yes - /ff anticheat op on turns
      // the checks on them *and* lets the failsafe act, because a switch that measures a body
      // but leaves the kick switched off is the veto that made the ladder look dead.
      source.sendSuccess(
         () -> Component.literal(
            Chat.colorize(
               "&7Operators may be kicked: "
                  + (ops || opKick ? "&ayes" : "&cno &8(/ff anticheat op on, or /ff anticheat op kick on)")
            )
         ),
         false
      );
      // The checks staff have called wrong, and what that has done to their bar. Silent when
      // nobody has filed a verdict, because a line reading "none" is a line nobody needs.
      java.util.List<String> reviewed = com.fortuneandfavors.anticheat.AntiCheatStore.reviewedChecks();
      if (!reviewed.isEmpty()) {
         source.sendSuccess(
            () -> Component.literal(Chat.colorize(
               "&7Called wrong by staff: &f" + String.join("&7, ", reviewed)
                  + " &8(their alert bar is raised - they still record, they just shout less)"
            )),
            false
         );
      }
      if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer statusPlayer) {
         source.sendSuccess(
            () -> Component.literal(Chat.colorize("&7Test mode: "
               + (com.fortuneandfavors.anticheat.AntiCheat.testMode(statusPlayer) ? "&aON" : "&7off")
               + " &8(/ff anticheat testmode)")),
            false
         );
         // The answer to "why is nothing being flagged", on the line the admin is already
         // reading: the two exemptions are silent from the outside and one of them is an
         // economy permission nobody would think to check.
         String skip = com.fortuneandfavors.anticheat.AntiCheat.exemptionReason(statusPlayer);
         source.sendSuccess(
            () -> Component.literal(
               Chat.colorize(
                  skip == null
                     ? "&7This body: &achecked"
                     : "&7This body: &cskipped - &f" + skip + "&8 (so a speed test on yourself would show nothing)"
               )
            ),
            false
         );
      }
      source.sendSuccess(
         () -> Component.literal(Chat.colorize("&7Checks: &f" + String.join("&7, &f", com.fortuneandfavors.anticheat.AntiCheat.CHECKS))),
         false
      );
      source.sendSuccess(
         () -> Component.literal(
            Chat.colorize(
               "&7Simulation, motion, speed prediction, flight prediction, ground spoof, Jesus, "
                  + "no-fall, no-slow, anti-hunger and timer are tracked as server-side patterns; "
                  + "heuristic movement checks never teleport by default. Reach uses the server "
                  + "hitbox, while hitbox expansion, killaura/6H, critical-ground, fast-swing, "
                  + "autoclicker and aim-constant/linear/module-360 are separate evidence streams. "
                  + "Scaffold, fast-use, x-ray, packets and impossible actions are recorded with "
                  + "lag-aware confidence. Alerts go to staff."
            )
         ),
         false
      );
      source.sendSuccess(
         () -> Component.literal(
            Chat.colorize(
               com.fortuneandfavors.anticheat.AntiCheatPolicy.isCustom()
                  ? "&7Enforcement policy: &eCUSTOM &8(&f/ff anticheat tune&8) - thresholds have been moved off this build's tested values."
                  : "&7Enforcement policy: &atesting defaults &8(&f/ff anticheat tune&8 to review)."
            )
         ),
         false
      );
      // The ladder, in the words an admin testing the failsafe needs. Every number is read off
      // the constants rather than typed, because "I flew around for a minute and nothing
      // happened" is the report this line exists to answer, and a line that drifts out of step
      // with the engine is worse than no line at all.
      source.sendSuccess(
         () -> Component.literal(
            Chat.colorize(
               "&7Failsafe kicks: "
                  + (ModConfig.anticheatFailsafe() ? "&aon" : "&coff &8(/ff anticheat failsafe on)")
                  + " &8- &7it acts on &f" + com.fortuneandfavors.anticheat.AntiCheat.ENFORCE_STRIKES
                  + "&7 enforcement-level findings at least &f"
                  + (com.fortuneandfavors.anticheat.AntiCheatPolicy.enforceCooldown() / 20L)
                  + "s&7 apart, a body hot on &f" + com.fortuneandfavors.anticheat.AntiCheat.ENFORCE_CORROBORATION
                  + "&7 physical checks at once counting as one, and it stands down entirely while any moderator is online."
            )
         ),
         false
      );
      if (!on) {
         source.sendSuccess(() -> Component.literal(Chat.colorize("&8Turn it on with &f/ff anticheat on&8.")), false);
      }
      return 1;
   }

   private static int ffAnticheatSet(CommandContext<CommandSourceStack> ctx, boolean on) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      ModConfig.setAnticheat(on);
      ModConfig.save(source.getServer());
      if (!on) {
         com.fortuneandfavors.anticheat.AntiCheat.clear();
      }
      source.sendSuccess(
         () -> Component.literal(
            Chat.colorize(
               on
                  ? "&c&l[AC]&r &aAnticheat enabled. &7Alerts go to staff; impossible hits, breaks and placements are refused."
                  : "&c&l[AC]&r &7Anticheat disabled - nothing is checked and the ledger is cleared."
            )
         ),
         true
      );
      // Turning it on is half the job, and the half that is invisible: an operator who runs
      // this and then tests a speed client on themselves is still exempt, and any detector
      // that is never asked anything looks exactly like one that is broken. Said here, at the
      // moment they would otherwise conclude the check does not work.
      if (on && source.getEntity() instanceof net.minecraft.server.level.ServerPlayer tester) {
         String skip = com.fortuneandfavors.anticheat.AntiCheat.exemptionReason(tester);
         if (skip != null) {
            source.sendSuccess(
               () -> Component.literal(
                  Chat.colorize("&c&l[AC]&r &eYou are still skipped: &f" + skip + " &7- run &f/ff anticheat op on&7 to be checked yourself.")
               ),
               true
            );
         }
      }
      return 1;
   }

   private static int ffAnticheatOps(CommandContext<CommandSourceStack> ctx, boolean on) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      // `op on` is the documented self-test switch. Previously it only changed the
      // exemption bit, so an admin who followed the instruction while the global
      // anticheat was off still had a completely inert system and saw no warning.
      // Enabling the test switch now also enables the anticheat; turning it off only
      // restores operator exemption and leaves the global server setting untouched.
      boolean enabledByTest = on && !ModConfig.anticheat();
      if (enabledByTest) {
         ModConfig.setAnticheat(true);
      }
      ModConfig.setAnticheatOps(on);
      ModConfig.save(source.getServer());
      source.sendSuccess(
         () -> Component.literal(
            Chat.colorize(
               on
                  ? "&c&l[AC]&r &aOperators are now checked &cand kickable&a, and the anticheat is ON. &7The ladder applies to staff exactly as it does to players while this is on - &f/ff anticheat op off&7 puts them back."
                  : "&c&l[AC]&r &7Operators are exempt again - they are skipped by every check and the failsafe will not act on them. &8The global anticheat setting was not changed."
            )
         ),
         true
      );
      return 1;
   }

   /**
    * {@code /ff anticheat op kick on|off} - whether the automatic half may act on staff.
    *
    * <p>Now the *other* road rather than the only one: {@code /ff anticheat op on} turns the
    * checks on staff and lets the failsafe act, because "it ignores OP no matter what" was the
    * report and a switch that measures a body while leaving the kick off is the same silent veto
    * in a smaller box. This remains for the opposite shape of server - an owner who wants staff
    * kickable while the checks still skip them - and either one is a single, explicit yes.
    */
   private static int ffAnticheatOpKick(CommandContext<CommandSourceStack> ctx, boolean on) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      ModConfig.setAnticheatOpKick(on);
      ModConfig.save(source.getServer());
      source.sendSuccess(
         () -> Component.literal(
            Chat.colorize(
               on
                  ? "&c&l[AC]&r &cOperators may now be kicked. &7The automatic ladder applies to staff exactly as it does to players - three kick lines, then a five-minute timeout - for as long as this is on."
                  : "&c&l[AC]&r &aOperators are protected again - the anticheat will not kick or time out staff, whatever else it sees."
            )
         ),
         true
      );
      return 1;
   }

   private static int ffAnticheatTestMode(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      try {
         net.minecraft.server.level.ServerPlayer player = source.getPlayerOrException();
         boolean on = com.fortuneandfavors.anticheat.AntiCheat.toggleTestMode(player);
         if (on) {
            // This command is explicitly for testing and must not silently do nothing
            // because the server shipped with detection disabled or the tester is OP.
            ModConfig.setAnticheat(true);
            ModConfig.setAnticheatOps(true);
            ModConfig.save(source.getServer());
         }
         source.sendSuccess(
            () -> Component.literal(
               Chat.colorize(
                  on
                     ? "&c&l[AC TEST]&r &aON&7 - live heartbeat and every finding will be shown to you. &f"
                        + com.fortuneandfavors.anticheat.AntiCheat.testStatus(player)
                     : "&c&l[AC TEST]&r &7OFF - normal anticheat behavior remains unchanged."
               )
            ),
            false
         );
         return 1;
      } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
         source.sendFailure(Component.literal(Chat.colorize("&c[AC TEST] Run this command in-game as a player.")));
         return 0;
      }
   }

   private static int ffAnticheatList(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      java.util.List<String> rows = com.fortuneandfavors.anticheat.AntiCheat.ledger(source.getServer());
      if (rows.isEmpty()) {
         source.sendSuccess(() -> Component.literal(Chat.colorize("&c&l[AC]&r &7Nothing flagged since the last restart.")), false);
         return 1;
      }
      source.sendSuccess(() -> Component.literal(Chat.colorize("&c&l[AC]&r &fFlagged since the last restart:")), false);
      for (String row : rows) {
         source.sendSuccess(() -> Component.literal(Chat.colorize(row)), false);
      }
      return 1;
   }

   /**
    * {@code /ff anticheat compat} - the client-mod compatibility modes, and what
    * each one is allowed to move.
    *
    * <p>Printed with both halves of every entry, on purpose. "We support Accurate
    * Block Placement" is the sentence that turns into an argument six months later
    * about why reach hacks are not being caught. What it actually relaxes, and what
    * it does not, is the answer, and it belongs next to the switch.
    */
   private static int ffAnticheatCompat(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      source.sendSuccess(
         () -> Component.literal(
            Chat.colorize("&c&l[AC]&r &fClient-mod compatibility &8- nothing here switches a check off")
         ),
         false
      );
      for (String line : com.fortuneandfavors.anticheat.QolCompat.describe()) {
         source.sendSuccess(() -> Component.literal(Chat.colorize(line)), false);
      }
      source.sendSuccess(
         () -> Component.literal(
            Chat.colorize(
               "&8Reach, the packet budget and every combat check stay on regardless. Toggle one "
                  + "with &f/ff feature <name>&8."
            )
         ),
         false
      );
      // The mods that need no entry, said out loud: "not handled" and "nothing to
      // handle" look the same from a command's output, and only one of them is fine.
      source.sendSuccess(
         () -> Component.literal(Chat.colorize("&8" + com.fortuneandfavors.anticheat.QolCompat.NEEDS_NO_ENTRY)),
         false
      );
      return 1;
   }

   /** {@code /ff anticheat alerts on|off} - whether staff see live alerts at all. */
   private static int ffAnticheatAlerts(CommandContext<CommandSourceStack> ctx, boolean on) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      com.fortuneandfavors.anticheat.AntiCheatStore.setAlerts(on);
      com.fortuneandfavors.anticheat.AntiCheatStore.save(source.getServer());
      source.sendSuccess(
         () -> Component.literal(Chat.colorize(on
            ? "&c&l[AC]&r &aAlerts on. &7Staff will be told when a check fires."
            : "&c&l[AC]&r &7Alerts off. &8Findings are still recorded and enforced; nobody is told.")),
         true
      );
      return 1;
   }

   /** {@code /ff anticheat failsafe on|off} - the automatic kick ladder. */
   private static int ffAnticheatFailsafe(CommandContext<CommandSourceStack> ctx, boolean on) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      ModConfig.setAnticheatFailsafe(on);
      ModConfig.save(source.getServer());
      source.sendSuccess(
         () -> Component.literal(Chat.colorize(on
            ? "&c&l[AC]&r &aFailsafe on. &7With no moderator online, a strong finding kicks (then briefly times out) on repeat. &8Never a permanent ban."
            : "&c&l[AC]&r &7Failsafe off. &8Findings are recorded and reported only; nothing is automatic.")),
         true
      );
      return 1;
   }

   /** {@code /ff anticheat info <player>} - everything known about one player. */
   private static int ffAnticheatInfo(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
      for (String line : com.fortuneandfavors.anticheat.AntiCheat.report(target)) {
         source.sendSuccess(() -> Component.literal(Chat.colorize(line)), false);
      }
      // What the client says it is. Kept at the END of the report on purpose: the checks are
      // the evidence, and a self-report read first is a self-report that colours everything
      // after it. It cannot raise a violation - see ClientContext.
      for (String line : com.fortuneandfavors.anticheat.ClientContext.describe(target)) {
         source.sendSuccess(() -> Component.literal(Chat.colorize(line)), false);
      }
      return 1;
   }

   /** {@code /ff anticheat banned} - the moderation screen, or its text form from console. */
   private static int ffAnticheatKick(CommandContext<CommandSourceStack> ctx, String reason) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      String name = StringArgumentType.getString(ctx, "player");
      net.minecraft.server.level.ServerPlayer target = source.getServer() == null
         ? null
         : source.getServer().getPlayerList().getPlayerByName(name);
      if (target == null) {
         source.sendFailure(Component.literal(Chat.colorize("&c&l[AC]&r &7No player called &f" + name + "&7 is online.")));
         return 0;
      }

      // Whether the connection actually closed is reported rather than assumed. A kick that
      // says "Kicked them" while the body was already gone is the message that makes "the
      // anticheat kick does not work" impossible to argue with from either side.
      boolean removed = com.fortuneandfavors.anticheat.AntiCheat.kick(target, reason, source.getTextName());
      String who = target.getName().getString();
      if (removed) {
         source.sendSuccess(
            () -> Component.literal(Chat.colorize("&c&l[AC]&r &aKicked " + who + " (&f" + reason + "&7). They can rejoin in a minute.")),
            true
         );
      } else {
         source.sendSuccess(
            () -> Component.literal(Chat.colorize(
               "&c&l[AC]&r &eFiled a kick against " + who + " (&f" + reason
                  + "&e), but their connection was already closed - nothing was cut. The re-entry window still applies."
            )),
            true
         );
      }
      return 1;
   }

   /**
    * {@code /ff anticheat clear <player>} - stop making one body wait: kick window, timeout,
    * ladder.
    *
    * <p>Two roads, and the second one is the point. A player who is *waiting* out a kick window or
    * a timeout is, by definition, not online - so a command that resolved its target through the
    * player list could only ever have worked for somebody who did not need it. Online is still
    * tried first, because a live uuid is the better key and a body in the world also has live
    * strikes in memory to drop; the record by name is the offline road, with the uuid that is the
    * only key an absent player has. Both go through the same {@code AntiCheat.clear}, so there is
    * one behaviour and not two. Deliberately not an unban: a ban is a decision a person made.
    */
   private static int ffAnticheatClear(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      String name = StringArgumentType.getString(ctx, "player");
      net.minecraft.server.level.ServerPlayer online = source.getServer() == null
         ? null
         : source.getServer().getPlayerList().getPlayerByName(name);
      if (online != null) {
         boolean cleared = com.fortuneandfavors.anticheat.AntiCheat.clear(online);
         String who = online.getName().getString();
         source.sendSuccess(
            () -> Component.literal(Chat.colorize(
               cleared
                  ? "&c&l[AC]&r &aCleared &f" + who
                     + "&a's kick window, timeout and ladder. They can rejoin now and are judged fresh."
                  : "&c&l[AC]&r &7" + who + " &7had nothing waiting - no kick window, timeout or ladder to clear."
            )),
            true
         );
         return 1;
      }
      com.fortuneandfavors.anticheat.AntiCheatStore.Record record =
         com.fortuneandfavors.anticheat.AntiCheatStore.byName(name);
      if (record == null) {
         source.sendFailure(Component.literal(Chat.colorize(
            "&c&l[AC]&r &7No anticheat record for &f" + name
               + "&7 - they are offline and have never been flagged, so there is nothing waiting to clear."
         )));
         return 0;
      }
      boolean cleared = com.fortuneandfavors.anticheat.AntiCheat.clear(record.id);
      String who = record.name == null || record.name.isBlank() ? name : record.name;
      source.sendSuccess(
         () -> Component.literal(Chat.colorize(
            cleared
               ? "&c&l[AC]&r &aCleared &f" + who
                  + "&a's kick window, timeout and ladder while they are offline. They can rejoin now and are judged fresh."
               : "&c&l[AC]&r &7" + who + " &7is offline and had nothing waiting - no kick window, timeout or ladder to clear."
         )),
         true
      );
      return 1;
   }

   private static int ffAnticheatBanned(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerPlayer viewer = source.getPlayer();
      if (viewer != null) {
         com.fortuneandfavors.menu.AntiCheatMenu.open(viewer, true);
         return 1;
      }
      java.util.List<String> rows = com.fortuneandfavors.anticheat.AntiCheatStore.describe();
      if (rows.isEmpty()) {
         source.sendSuccess(() -> Component.literal(Chat.colorize("&c&l[AC]&r &7Nobody is punished or timed out.")), false);
         return 1;
      }
      source.sendSuccess(() -> Component.literal(Chat.colorize("&c&l[AC]&r &fPunishments on file:")), false);
      for (String row : rows) {
         source.sendSuccess(() -> Component.literal(Chat.colorize(row)), false);
      }
      return 1;
   }

   /**
    * {@code /ff anticheat banned <player>} - the records screen, on one player's case file.
    *
    * <p>The screen half of a command that used to be a wall of text: from a console there is
    * nothing to open, so the same three lines are printed, which is why both paths are here.
    */
   private static int ffAnticheatBannedPlayer(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      String name = StringArgumentType.getString(ctx, "player");
      com.fortuneandfavors.anticheat.AntiCheatStore.Record record = resolveAnticheatRecord(source, name);
      if (record == null) {
         source.sendFailure(Component.literal(Chat.colorize("&cNo anticheat record for &f" + name + "&c - they have never been flagged.")));
         return 0;
      }
      net.minecraft.server.level.ServerPlayer viewer = source.getPlayer();
      if (viewer != null) {
         com.fortuneandfavors.menu.AntiCheatMenu.openCase(viewer, record.id, true);
         return 1;
      }
      for (String line : describeAnticheatRecord(record)) {
         source.sendSuccess(() -> Component.literal(Chat.colorize(line)), false);
      }
      return 1;
   }

   /**
    * {@code /ff anticheat unban <player>} - every active punishment lifted, no screen involved.
    *
    * <p>Name-addressed on purpose, and the reason is where it is run from: a console has no
    * player to resolve, and the person being unbanned is almost always offline - that is what
    * being banned means. The store keys on the uuid, so the name is only a way of finding it.
    */
   private static int ffAnticheatUnban(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      String name = StringArgumentType.getString(ctx, "player");
      com.fortuneandfavors.anticheat.AntiCheatStore.Record record = resolveAnticheatRecord(source, name);
      if (record == null) {
         source.sendFailure(Component.literal(Chat.colorize("&cNo anticheat record for &f" + name + "&c - nothing to lift.")));
         return 0;
      }
      boolean lifted = com.fortuneandfavors.anticheat.AntiCheatStore.unban(record.id, source.getTextName());
      source.sendSuccess(
         () -> Component.literal(Chat.colorize(
            lifted
               ? "&c&l[AC]&r &aEvery active punishment on &f" + record.name + "&a is lifted, and the automatic ladder is reset."
               : "&c&l[AC]&r &7Nothing was in force on &f" + record.name + "&7."
         )),
         true
      );
      return lifted ? 1 : 0;
   }

   /** Online first - a live uuid is the better key - then the name on the record. */
   private static com.fortuneandfavors.anticheat.AntiCheatStore.Record resolveAnticheatRecord(
      CommandSourceStack source, String name
   ) {
      net.minecraft.server.level.ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(name);
      com.fortuneandfavors.anticheat.AntiCheatStore.Record record = online == null
         ? null
         : com.fortuneandfavors.anticheat.AntiCheatStore.get(online.getUUID());
      return record != null ? record : com.fortuneandfavors.anticheat.AntiCheatStore.byName(name);
   }

   private static java.util.List<String> describeAnticheatRecord(
      com.fortuneandfavors.anticheat.AntiCheatStore.Record record
   ) {
      java.util.List<String> out = new java.util.ArrayList<>();
      long now = System.currentTimeMillis();
      com.fortuneandfavors.anticheat.AntiCheatStore.Punishment active = record.activePunishment(now);
      out.add("&c&l[AC]&r &f" + record.name + " &8(" + record.id + ")");
      out.add("&7Failures on file: &f" + record.total() + " &7worst vl &f" + Math.round(record.worstLevel())
         + " &7punishments: &f" + record.punishments.size() + " &7reviews: &f" + record.reviews.size());
      out.add(
         record.timedOut(now)
            ? "&cTimed out for another " + ((record.timeoutUntil - now) / 1000L) + "s &8(/ff anticheat unban " + record.name + ")"
            : (active != null
               ? "&eActive " + active.type() + "&7: " + active.reason()
               : "&7No active punishment")
      );
      return out;
   }

   private static int ffAnticheatWatch(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerPlayer staff = source.getPlayerOrException();
      net.minecraft.server.level.ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
      if (com.fortuneandfavors.anticheat.AntiCheat.watch(staff, target)) {
         source.sendSuccess(
            () -> Component.literal(Chat.colorize("&c&l[AC]&r &aNow watching &f" + target.getName().getString() + "&a. &7Their alerts come to you in full, with the action buttons.")),
            true
         );
      }
      return 1;
   }

   private static int ffAnticheatUnwatch(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerPlayer staff = source.getPlayerOrException();
      net.minecraft.server.level.ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
      if (com.fortuneandfavors.anticheat.AntiCheat.unwatch(staff, target)) {
         source.sendSuccess(() -> Component.literal(Chat.colorize("&c&l[AC]&r &7Stopped watching &f" + target.getName().getString() + "&7.")), true);
      }
      return 1;
   }

   private static int ffAnticheatSpectate(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerPlayer staff = source.getPlayerOrException();
      net.minecraft.server.level.ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
      if (!com.fortuneandfavors.anticheat.AntiCheat.spectate(staff, target)) {
         source.sendFailure(Component.literal(Chat.colorize("&c[AC] &7Could not start spectating (are you the target?).")));
      }
      return 1;
   }

   private static int ffAnticheatUnspectate(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerPlayer staff = source.getPlayerOrException();
      if (!com.fortuneandfavors.anticheat.AntiCheat.unspectate(staff)) {
         source.sendFailure(Component.literal(Chat.colorize("&c[AC] &7You were not spectating anyone.")));
      }
      return 1;
   }

   private static int ffAnticheatSimulateSelf(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerPlayer self = source.getPlayer();
      if (self == null) {
         source.sendFailure(Component.literal(Chat.colorize("&c[AC] &7Name a player when running this from console.")));
         return 0;
      }
      return ffAnticheatSimulateLines(source, self);
   }

   private static int ffAnticheatSimulate(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.      ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
      int result = ffAnticheatSimulateLines(source, target);
      for (String line : com.fortuneandfavors.anticheat.ClientContext.describe(target)) {
         source.sendSuccess(() -> Component.literal(Chat.colorize(line)), false);
      }
      return result;
   }

   private static int ffAnticheatSimulateLines(CommandSourceStack source, net.minecraft.server.level.ServerPlayer target) {
      for (String line : com.fortuneandfavors.anticheat.AntiCheat.simulate(target)) {
         source.sendSuccess(() -> Component.literal(Chat.colorize(line)), false);
      }
      return 1;
   }

   /**
    * {@code /ff anticheat why [player]} - the answer to "is this player clean, or is
    * nothing looking?"
    *
    * <p>An empty ledger is the same output for both, which is how a detector that has
    * quietly stood down reads as a detector that has found nothing. Every gate is
    * printed in the order it is applied, so whichever line reads wrong is the reason.
    */
   private static int ffAnticheatWhySelf(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerPlayer self = source.getPlayer();
      if (self == null) {
         source.sendFailure(Component.literal(Chat.colorize("&c[AC] &7Name a player when running this from console.")));
         return 0;
      }
      return ffAnticheatWhyLines(source, self);
   }

   private static int ffAnticheatWhy(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      return ffAnticheatWhyLines(source, EntityArgument.getPlayer(ctx, "player"));
   }

   private static int ffAnticheatWhyLines(CommandSourceStack source, net.minecraft.server.level.ServerPlayer target) {
      source.sendSuccess(
         () -> Component.literal(Chat.colorize("&c&l[AC]&r &fWhy " + target.getName().getString() + " is or is not being judged:")),
         false
      );
      for (String line : com.fortuneandfavors.anticheat.AntiCheat.diagnostics(target)) {
         source.sendSuccess(() -> Component.literal(Chat.colorize("&8  &7" + line)), false);
      }
      return 1;
   }

   /**
    * {@code /ff anticheat probe} - the detector's own arithmetic, with no client
    * involved.
    *
    * <p>Every live test of a speed check needs a hacked client, so "the detector is
    * broken" and "the client is broken" produce identical evidence. A synthetic trace
    * removes the second possibility: it drives the production window and the production
    * model, so if the honest ceilings come back clean and the hacked ones come back
    * flagged, the detector works and the problem is on the client side of the wire.
    */
   private static int ffAnticheatProbe(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      source.sendSuccess(
         () -> Component.literal(Chat.colorize("&c&l[AC]&r &fDetector self-check - the real model, driven by a synthetic trace:")),
         false
      );
      for (String line : com.fortuneandfavors.anticheat.AntiCheat.probeReport()) {
         source.sendSuccess(() -> Component.literal(Chat.colorize(line)), false);
      }
      return 1;
   }

   /** {@code /ff anticheat exempt <player> <check|*> <seconds>} - the exemption API. */
   private static int ffAnticheatExempt(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
      String check = StringArgumentType.getString(ctx, "check");
      int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
      String by = source.getTextName();
      com.fortuneandfavors.anticheat.AntiCheat.allow(target, check, seconds, "granted by " + by, by);
      com.fortuneandfavors.anticheat.AntiCheatStore.save(source.getServer());
      source.sendSuccess(
         () -> Component.literal(Chat.colorize("&c&l[AC]&r &aExempted &f" + target.getName().getString() + "&7 from &f" + check + " &7for &f" + seconds + "s&7.")),
         true
      );
      return 1;
   }

   private static int ffAnticheatUnexempt(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
      String check = StringArgumentType.getString(ctx, "check");
      int removed = com.fortuneandfavors.anticheat.AntiCheat.clearExemptions(target, check);
      com.fortuneandfavors.anticheat.AntiCheatStore.save(source.getServer());
      source.sendSuccess(
         () -> Component.literal(Chat.colorize(removed > 0
            ? "&c&l[AC]&r &7Removed &f" + removed + "&7 exemption(s) for &f" + check + "&7 on &f" + target.getName().getString() + "&7."
            : "&c&l[AC]&r &7Nothing to remove for &f" + check + "&7.")),
         true
      );
      return 1;
   }

   /** {@code /ff anticheat review <player> <check> confirm|false} - the verdict. */
   private static int ffAnticheatReview(CommandContext<CommandSourceStack> ctx, boolean falsePositive) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      net.minecraft.server.level.ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
      String check = StringArgumentType.getString(ctx, "check");
      String by = source.getTextName();
      com.fortuneandfavors.anticheat.AntiCheatStore.review(target, check, falsePositive, by, "command");
      com.fortuneandfavors.anticheat.AntiCheatStore.save(source.getServer());
      source.sendSuccess(
         () -> Component.literal(Chat.colorize(falsePositive
            ? "&a&l[AC]&r &7Marked &f" + check + "&7 a false positive for &f" + target.getName().getString() + "&7. Evidence kept."
            : "&c&l[AC]&r &7Confirmed &f" + check + "&7 on &f" + target.getName().getString() + "&7.")),
         true
      );
      return 1;
   }

   private static int ffVersion(CommandContext<CommandSourceStack> ctx) {
      String version = "unknown";

      try {
         Optional<ModContainer> container = FabricLoader.getInstance().getModContainer("fortuneandfavors");
         if (container.isPresent()) {
            version = container.get().getMetadata().getVersion().getFriendlyString();
         }
      } catch (Exception var5) {
      }

      boolean fresh = false;

      for (ShopEntry e : ShopData.entries(Category.EXCLUSIVE)) {
         if ("item_forge".equals(ModItems.typeOf(e.stack()))) {
            fresh = true;
            break;
         }
      }

      String runningVersion = version;
      boolean freshBuild = fresh;
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
               Chat.colorize(
                  "&6&lFortune & Favors&r &f&lv"
                     + runningVersion
                     + "&r\n"
                     + (
                        freshBuild
                           ? "&aItem Forge in shop catalog ✓ &7- fresh build, all systems current."
                           : "&cItem Forge missing from shop catalog ✗ &7- this jar is out of date! Replace it in the mods folder and restart."
                     )
               )
            ),
            false
         );
      return 1;
   }

   private static int ffLeaveRealm(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      String err = BossManager.escapeSnowRealm(player);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return 0;
      } else {
         return 1;
      }
   }

   private static int ffBackup(CommandContext<CommandSourceStack> ctx) {
      WorldBackup.backupWorld(((CommandSourceStack)ctx.getSource()).getServer());
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
               Chat.colorize(
                  "&aWorld backup created! &7Snapshots live next to the world folder in &ffortuneandfavors_backups&7. Keeping the &f"
                     + ModConfig.backupKeep()
                     + "&7 newest snapshots."
               )
            ),
            false
         );
      return 1;
   }

   /**
    * {@code /ff vanish} - toggle stealth, and say which way it went.
    *
    * <p>The command used to be silent in both directions, which is the one thing a stealth mode
    * cannot afford: the operator's only feedback was whether other players could still see them,
    * and "did that work?" is precisely the question a vanish is for. It now answers in private
    * chat, because the answer is for the staff member and nobody else.
    */
   private static int ffVanish(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      VanishManager.toggle(player);
      reportVanishState(player);
      return 1;
   }

   /**
    * {@code /ff vanish on|off} - the same switch with the direction named.
    *
    * <p>Worth its own spelling because the toggle is a state a staff member is already unsure
    * about - that is why they are typing - and "on" has to mean on even when they were already
    * hidden. A script or a habit that says {@code /ff vanish on} has to leave the operator
    * invisible; a toggle cannot promise that, and the one time it is wrong the operator does not
    * notice until somebody answers their chat.
    */
   private static int ffVanishSet(CommandContext<CommandSourceStack> ctx, boolean on) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      if (on) {
         VanishManager.vanish(player);
      } else {
         VanishManager.unvanish(player);
      }
      reportVanishState(player);
      return 1;
   }

   /** One private line naming the state the operator ended up in. */
   private static void reportVanishState(ServerPlayer player) {
      boolean hidden = VanishManager.isVanished(player);
      if (hidden) {
         Chat.msg(
            player,
            // \u2726 rather than a check mark: it is the glyph this mod already uses everywhere in
            // chat, and it is one the vanilla font actually carries.
            "&6&lFF Vanish&r &a\u2726 you are hidden. &7Off the tab list, invisible, off the locator bar, "
               + "muted, and your armour and held items are stashed. &f/ff vanish status &7lists the whole state; "
               + "&f/ff vanish off &7brings you back."
         );
      } else {
         Chat.msg(player, "&6&lFF Vanish&r &7you are visible again - gear returned and team restored.");
      }
   }

   /**
    * {@code /ff vanish status} - who is hidden right now, and whether each vanish is actually holding.
    *
    * <p>"Vanish does not work" is the report this command exists for, and the honest first
    * question is whether it is working for anybody: every mute a vanish has is a flag another
    * system can clear, so the count of times this mod had to put one back is the number that
    * decides whether the next step is a bug report or a conversation about who has the other
    * plugin. It prints the diagnostics next to the state rather than only the state, because the
    * state is the part that looks fine whenever the bug is not happening.
    */
   private static int ffVanishStatus(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack source = (CommandSourceStack)ctx.getSource();
      MinecraftServer server = source.getServer();
      ServerPlayer self = source.getPlayer();
      java.util.Set<java.util.UUID> ids = VanishManager.vanishedIds();
      source.sendSystemMessage(Component.literal(Chat.colorize(
         "&6&lFF Vanish&r &7- &f" + ids.size()
            + (ids.size() == 1 ? " &7hidden body." : " &7hidden bodies.")
      )));
      if (ids.isEmpty()) {
         if (self != null) {
            source.sendSystemMessage(Component.literal(Chat.colorize(
               "&7You are &fvisible&7. &f/ff vanish on &7hides you."
            )));
         }
         return 1;
      }
      for (java.util.UUID id : ids) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p == null) {
            source.sendSystemMessage(Component.literal(Chat.colorize(
               "&7  &f" + id + " &8(hidden, but not online - their vanish is cleared on the way out)"
            )));
            continue;
         }
         source.sendSystemMessage(Component.literal(Chat.colorize(
            "&7  &f" + p.getScoreboardName() + " &7in &f" + p.level().dimension().identifier()
               + "\n&8    &7flags put back &f" + VanishManager.rewrites(p) + " &7x &8| &7synced effect particles &f"
               + VanishManager.effectParticleCount(p) + " &8| &7slots emptied &f" + VanishManager.emptiedSlots(p).size()
               + " &8| &7in the visible-body set &f"
               + (VanishManager.carriesMarker(p) ? "yes" : "&cno")
         )));
      }
      if (self != null && VanishManager.isVanished(self)) {
         source.sendSystemMessage(Component.literal(Chat.colorize(
            "&7Your chat is muted while hidden - this report is the only voice you have, and "
               + "&f/ff vanish off &7gives the rest back."
         )));
      }
      return 1;
   }

   /** /ff afk - mark yourself away (or clear it). The tab list shows the
    *  [AFK] marker, and standing still for 5 minutes auto-triggers it. */
   private static int ffAfk(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
      ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
      AfkManager.toggle(player);
      TagManager.refreshTabList(player.level().getServer());
      return 1;
   }

   /** Resolves a /ff give name. Also supports a level suffix for custom
    *  enchant tomes, e.g. "/ff give lifesteal 3" or "frostbite v". */
   private static ItemStack modItem(String name, net.minecraft.core.HolderLookup.Provider holders) {
      String normalized = name.toLowerCase(Locale.ROOT).trim();
      int sp = normalized.lastIndexOf(' ');
      if (sp > 0) {
         String tail = normalized.substring(sp + 1).trim();
         int level = -1;
         if (tail.matches("[1-5]")) {
            level = Integer.parseInt(tail);
         } else if (tail.matches("[ivx]+")) {
            level = switch (tail) {
               case "i" -> 1;
               case "ii" -> 2;
               case "iii" -> 3;
               case "iv" -> 4;
               case "v" -> 5;
               default -> -1;
            };
         }
         if (level >= 1) {
            ItemStack base = modItemBase(normalized.substring(0, sp).trim(), holders);
            if (base != null && CustomEnchantments.isTome(base)) {
               return CustomEnchantments.tome(CustomEnchantments.tomeEnchantment(base), level);
            }
            // CCEnchantments tomes: re-apply the level onto the base book.
            if (base != null && ccTomeKey(base) != null) {
               return ccTome(ccTomeKey(base), level);
            }
            // Bolt Bringer books: rebuild at the requested level.
            if (base != null && CCEnchantments.isBoltBringerBook(base)) {
               return CCEnchantments.boltBringerBook(holders, level);
            }
         }
      }
      // Appended level: "lifestealtome3" (alias form).
      if (normalized.length() > 1) {
         char last = normalized.charAt(normalized.length() - 1);
         if (last >= '2' && last <= '5') {
            ItemStack base = modItemBase(normalized.substring(0, normalized.length() - 1), holders);
            if (base != null && CustomEnchantments.isTome(base)) {
               return CustomEnchantments.tome(CustomEnchantments.tomeEnchantment(base), last - '0');
            }
            if (base != null && CCEnchantments.isBoltBringerBook(base)) {
               return CCEnchantments.boltBringerBook(holders, last - '0');
            }
         }
      }
      return modItemBase(normalized, holders);
   }

   /** The Lunge Spear: a vanilla netherite spear carrying the vanilla
    *  minecraft:lunge enchantment at max level (III). */
   private static ItemStack lungeSpear(net.minecraft.core.HolderLookup.Provider holders) {
      ItemStack spear = new ItemStack(Items.NETHERITE_SPEAR);
      spear.enchant(holders.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(net.minecraft.world.item.enchantment.Enchantments.LUNGE), 3);
      return spear;
   }

   private static ItemStack modItemBase(String name, net.minecraft.core.HolderLookup.Provider holders) {
      return switch (name.toLowerCase(Locale.ROOT)) {
         case "claimer", "chunk_claimer", "chunkclaimer" -> ModItems.chunkClaimer();
         case "buysign", "buy_sign" -> ModItems.buySign();
         case "sellsign", "sell_sign" -> ModItems.sellSign();
         case "autosell", "auto_sell", "autosellhopper" -> ModItems.autoSellHopper();
         case "upwards", "upward", "upwardshopper", "up_hopper", "uphopper" -> ModItems.upwardsHopper();
         case "elevator" -> ModItems.elevator();
         case "redeemer", "token_redeemer" -> ModItems.tokenRedeemer();
         case "infuser", "spawner_infuser" -> ModItems.spawnerInfuser();
         case "chair" -> ModItems.chair();
         case "deathcompass", "death_compass", "gravecompass", "grave_compass" -> ModItems.deathCompass();
         case "bountycompass", "bounty_compass", "huntercompass" -> ModItems.bountyCompass();
         case "repairmembrane", "repair_membrane", "membrane", "repair" -> ModItems.repairMembrane();
         case "sculkfood", "sculk_food", "sculkfruit", "sculk_fruit", "fruit" -> ModItems.sculkFood();
         case "lunge", "spear", "lungespear", "lungetome", "lunge_tome" -> lungeSpear(holders);
         case "itemforge", "item_forge", "forge" -> ModItems.itemForge();
         case "wormhole", "wormhole_potion" -> ModItems.wormholePotion();
         case "mystery", "mystery_box" -> ModItems.mysteryBox();
         case "raidtoken", "raid_boss_token", "raid", "witheringmemory", "withering_memory", "memory" -> ModItems.raidBossToken();
         case "kingloot", "king_loot", "lootbox", "loot_box" -> ModItems.kingLootBox();
         case "witherloot", "wither_loot", "wither_loot_box", "witherbox" -> ModItems.witherLootBox();
         case "raidlootbox", "raid_loot_box", "raidbox", "raidloot" -> ModItems.raidLootBox();
         case "slimebox", "slime_loot", "slime_loot_box" -> ModItems.slimeLootBox();
         case "witherstaff", "wither_staff", "staff" -> ModItems.witherStaff();
         case "multidimensional", "multidimensional_army", "army", "ascendedstaff", "ascended_staff" -> ModItems.multidimensionalArmy();
         case "witherblade", "wither_blade", "blade" -> ModItems.witherBlade();
         case "withercrown", "wither_crown", "crown" -> ModItems.witherCrown();
         case "withercloaksword", "wither_cloak_sword", "cloaksword" -> ModItems.witherCloakSword();
         case "kingbone", "king_bone", "witheressence", "withere", "essence" -> ModItems.kingBone();
         case "slimetoken", "slime_token", "slimeking", "gelatinous", "gelatinous_crown", "gelatinouscrown", "crown_token" -> ModItems.slimeBossToken();
         case "slimelauncher", "slime_launcher", "launcher" -> ModItems.slimeLauncher();
         case "slimeshield", "slime_shield" -> ModItems.slimeShield();
         case "slimeboots", "slime_boots" -> ModItems.slimeBoots();
         case "slimecore", "slime_core", "core", "gelatin", "mythicalgelatin" -> ModItems.slimeCore();
         case "golemtoken", "golem_token", "stonegolem", "boulder", "boulderbaby", "boulder_baby" -> ModItems.stoneGolemToken();
         case "golemcore", "golem_core" -> ModItems.golemCore();
         case "stonestaff", "stone_staff" -> ModItems.stoneStaff();
         case "golemfist", "golem_fist", "fist" -> ModItems.golemFist();
         case "stoneheart", "stone_heart" -> ModItems.stoneHeart();
         case "golembox", "golem_loot", "golem_loot_box", "golemloot" -> ModItems.golemLootBox();
         case "mindbox", "mind_loot", "mind_loot_box", "mindbinderbox" -> ModItems.mindLootBox();
         case "mindbinder", "mindbinder_eye", "mind_eye", "ominous", "ominous_eye", "ominouseye" -> ModItems.mindbinderEye();
         case "shatteredmind", "shattered_mind", "mindcore" -> ModItems.shatteredMind();
         case "mindstaff", "mind_staff", "staff_of_the_mindbinder" -> ModItems.mindbinderStaff();
         case "possessedmask", "possessed_mask", "mask" -> ModItems.possessedMask();
         case "mindshroud", "mind_shroud", "shroud" -> ModItems.mindbinderShroud();
         case "snow", "snowqueen", "icequeen", "snow_queen", "cryogenic", "cryogenic_core", "cryogeniccore" -> ModItems.snowQueenToken();
         case "snowlootbox", "snow_loot_box", "snowbox" -> ModItems.snowLootBox();
         case "spacetime rift", "spacetime_rift", "spacetimerift", "rift", "timelord", "timelordtoken", "time_lord" -> ModItems.spaceTimeRift();
         case "timelordloot", "timelordbox", "time_lord_loot_box", "timeloot", "timebox" -> ModItems.timeLordLootBox();
         case "marionette", "woodenmarionette", "wooden_marionette", "puppet", "puppeteer" -> ModItems.woodenMarionette();
         case "puppeteerloot", "puppeteerbox", "puppeteer_loot_box", "puppetbox" -> ModItems.puppeteerLootBox();
         case "puppeteersmask", "puppeteers_mask", "puppetmask" -> ModItems.puppeteersMask();
         case "marionettestrings", "marionette_strings", "puppetstrings" -> ModItems.marionetteStrings();
         case "emptymask", "empty_mask", "theemptymask", "blankmask" -> ModItems.emptyMask();
         case "pocketwatch", "pocket_watch", "watch" -> ModItems.pocketWatch(1);
         case "pocketwatch2", "pocket_watch_2", "watch2", "pocketwatchii" -> ModItems.pocketWatch(2);
         case "chronoshard", "chrono_shard", "chrono", "shard" -> ModItems.chronoShard();
         case "hourglass", "hourglassofhaste", "hourglass_of_haste", "hasteglass" -> ModItems.hourglassOfHaste();
         case "scarletblood", "scarlet_blood", "scarlet", "remilia", "bloodvial", "blood_vial" -> ModItems.scarletBlood();
         case "scarletlootbox", "scarlet_loot_box", "scarletbox", "scarletloot" -> ModItems.scarletLootBox();
         case "scarlettrophy", "scarlet_trophy", "deviltrophy" -> ModItems.scarletTrophy();
         case "bloodcore", "bloodsoakedcore", "bloodsoaked_core", "bloodsoaked" -> ModItems.bloodsoakedCore();
         case "scarletfang", "scarlets_fang", "scarlet_fang", "fang" -> ModItems.scarletFang();
         case "scarletgrimoire", "scarlet_grimoire", "grimoire", "bloodbook", "spellbook" -> ModItems.scarletGrimoire();
         case "bloodprism", "blood_prism", "prism", "ritualprism" -> ModItems.bloodPrism();
         case "clockworkcore", "clockwork_core", "clockwork", "windupkey", "wind_up_key" -> ModItems.clockworkCore();
         case "clockworklootbox", "clockwork_loot_box", "clockworkbox", "kingbox" -> ModItems.clockworkLootBox();
         case "clockworktrophy", "clockwork_trophy", "kingtrophy", "cog" -> ModItems.clockworkTrophy();
         case "mechscrap", "mech_scrap", "scrap", "mechanicalscrap" -> ModItems.mechScrap();
         case "clockworkgauntlet", "clockwork_gauntlet", "gauntlet" -> ModItems.clockworkGauntlet();
         case "mechanicalheart", "mechanical_heart", "clockworkheart" -> ModItems.mechanicalHeart();
         case "automatonarmor", "automaton_armor", "automaton" -> ModItems.automatonArmor();
         case "astralcompass", "astral_compass", "compass", "magister", "magistertoken" -> ModItems.astralCompass();
         case "starboundlootbox", "starbound_loot_box", "starboundbox", "magisterbox" -> ModItems.starboundLootBox();
         case "starboundtrophy", "starbound_trophy", "magistertrophy", "star" -> ModItems.starboundTrophy();
         case "magicalessence", "magical_essence", "magicessence" -> ModItems.magicalEssence();
         case "starpiercer", "star_piercer", "piercer" -> ModItems.starpiercer();
         case "astralmantle", "astral_mantle", "mantle" -> ModItems.astralMantle();
         case "magisterscodex", "magisters_codex", "codex" -> ModItems.magistersCodex();
         case "voidanchor", "void_anchor", "anchor", "voidshaper", "shaper" -> ModItems.voidAnchor();
         case "voidshaperlootbox", "voidshaper_loot_box", "voidbox", "shaperbox" -> ModItems.voidshaperLootBox();
         // The sea boss and the sky boss. Their summons are named for what they are rather than
         // for the boss, because "sovereign" and "sigil" were both spoken for years ago.
         case "seasovereign", "sovereignsheart", "sovereigns_heart", "heartofthedeep", "drowned" -> ModItems.sovereignsHeart();
         case "drownedlootbox", "drowned_loot_box", "drownedbox", "seabox" -> ModItems.drownedLootBox();
         case "grasp", "leviathansgrasp", "leviathans_grasp", "claw" -> ModItems.leviathansGrasp();
         case "tidecaller", "tide_caller", "wavecaller" -> ModItems.tidecaller();
         case "abyssalchain", "abyssal_chain", "chain" -> ModItems.abyssalChain();
         case "galesigil", "gale_sigil", "gale" -> ModItems.galeSigil();
         case "galelootbox", "gale_loot_box", "galebox", "skybox" -> ModItems.galeLootBox();
         case "abyssalpearl", "abyssal_pearl", "pearl" -> ModItems.abyssalPearl();
         case "galecore", "gale_core" -> ModItems.galeCore();
         case "skybreaker", "sky_breaker", "greatsword" -> ModItems.skybreaker();
         case "galechakram", "gale_chakram", "chakram", "ringblade" -> ModItems.galeChakram();
         case "wardensmantle", "wardens_mantle", "galechestplate" -> ModItems.wardensMantle();
         case "colossustrophy", "colossus_trophy", "shapedtrophy" -> ModItems.colossusTrophy();
         case "voidsteelscrap", "voidsteel_scrap", "voidsteel" -> ModItems.voidsteelScrap();
         case "voidreaver", "void_reaver", "reaver" -> ModItems.voidReaver();
         case "colossusplate", "colossus_plate", "plate" -> ModItems.colossusPlate();
         case "shapingsigil", "shaping_sigil", "sigil" -> ModItems.shapingSigil();
         case "heartoftheend", "heart_of_the_end", "heart", "dragonheart" -> ModItems.heartOfTheEnd();
         case "dragonscale", "dragon_scale", "scale" -> ModItems.dragonScale();
         // "fang" and "starfall" are already taken (the Scarlet Fang and the Starfall
         // enchantment tome), so the two weapons that would have used them answer to
         // their full names only. A second alias would be a duplicate case label.
         case "voidfang", "void_fang", "riftblade" -> ModItems.voidfang();
         case "starfallbow", "starfall_bow", "star_fall_bow", "enderbow" -> ModItems.starfall();
         case "enderheart", "ender_heart", "dragonmace" -> ModItems.enderheart();
         // The reforged tier. Names only as well, for the same reason: the plain words are
         // spoken for, so the awakened weapons answer to their own full names.
         case "voidfangawakened", "voidfang_awakened", "awakenedvoidfang", "voidfang2" -> ModItems.voidfangAwakened();
         case "starfallawakened", "starfall_awakened", "awakenedstarfall", "starfallbowawakened" -> ModItems.starfallAwakened();
         case "enderheartawakened", "enderheart_awakened", "awakenedenderheart", "enderheart2" -> ModItems.enderheartAwakened();
         case "sovereignscrown", "sovereigns_crown", "emeraldcrown", "sovereign", "emeraldking" -> ModItems.sovereignsCrown();
         case "sovereignlootbox", "sovereign_loot_box", "sovereignbox", "kingbox2" -> ModItems.sovereignLootBox();
         case "sovereigntrophy", "sovereign_trophy", "thronetrophy" -> ModItems.sovereignTrophy();
         case "royaltribute", "royal_tribute", "tribute" -> ModItems.royalTribute();
         case "royalcontract", "royal_contract", "contract" -> ModItems.royalContract();
         case "sovereignsbell", "sovereigns_bell", "bell" -> ModItems.sovereignsBell();
         case "emeraldseal", "emerald_seal", "seal" -> ModItems.emeraldSeal();
         case "agingtome", "aging_tome", "aging" -> CustomEnchantments.tome(CustomEnchantments.AGING, 1);
         case "icestaff", "ice_staff" -> ModItems.iceStaff();
         case "frozenheart", "frozen_heart" -> ModItems.frozenHeart();
         case "frostboundbow", "frostbound_bow", "frostboundcrown" -> ModItems.frostboundCrown();
         case "glaciercloak", "glacier_cloak" -> ModItems.glacierCloak();
         case "medallion", "sculk_medallion", "sculkmedallion", "sculktoken" -> ModItems.sculkMedallion();
         case "sculkbox", "sculk_loot", "sculk_loot_box", "wardenbox", "elderwardenbox" -> ModItems.sculkLootBox();
         case "sculkstaff", "sculk_staff", "sculk_mage_staff", "magescaff" -> ModItems.sculkMageStaff();
         case "sculkleggings", "sculk_leggings", "sensorleggings", "sculk_sensor_leggings" -> ModItems.sculkSensorLeggings();
         case "wardenscall", "wardens_call", "warden_call", "horn" -> ModItems.wardensCall();
         case "sculkessence", "sculk_essence" -> ModItems.sculkEssence();
         case "rune", "runes", "rune_haste", "runeofhaste", "haste" -> ModItems.runeOfHaste();
         case "runeflame", "rune_flame", "runeofflame", "flamerune" -> ModItems.runeOfFlame();
         case "runefortune", "rune_fortune", "runeoffortune", "fortunerune" -> ModItems.runeOfFortune();
         case "runeswiftness", "rune_swiftness", "runeofswiftness", "swiftnessrune" -> ModItems.runeOfSwiftness();
         case "chainfire", "chainfiretome" -> ccTome(CCEnchantments.CHAINFIRE, 1);
         case "sniper", "snipertome" -> ccTome(CCEnchantments.SNIPER, 1);
         case "revolver", "revolvertome" -> ccTome(CCEnchantments.REVOLVER, 1);
         case "sharpshooter", "sharpshootertome" -> ccTome(CCEnchantments.SHARPSHOOTER, 1);
         case "recoil", "recoiltome" -> ccTome(CCEnchantments.RECOIL, 1);
         case "huntersmark", "hunters_mark", "huntersmarktome" -> ccTome(CCEnchantments.HUNTERS_MARK, 1);
         case "heavybolt", "heavy_bolt", "heavybolttome" -> ccTome(CCEnchantments.HEAVY_BOLT, 1);
         case "curseoffragility", "curse_fragility", "fragility", "fragilitytome" -> ccTome(CCEnchantments.CURSE_FRAGILITY, 1);
         case "combo", "combotome" -> ccTome(CCEnchantments.COMBO, 1);
         case "berserker", "berserkertome" -> ccTome(CCEnchantments.BERSERKER, 1);
         case "parry", "parrytome" -> CustomEnchantments.tome(CustomEnchantments.PARRY, 1);
         case "counter", "countertome" -> CustomEnchantments.tome(CustomEnchantments.COUNTER, 1);
         case "soulbind", "soulbindtome" -> CustomEnchantments.tome(CustomEnchantments.SOULBIND, 1);
         case "pointblank", "point_blank", "pointblanktome" -> CustomEnchantments.tome(CustomEnchantments.POINT_BLANK, 1);
         case "deadeye", "deadeyetome" -> CustomEnchantments.tome(CustomEnchantments.DEADEYE, 1);
         case "crabclaw", "crab_claw", "crabclawtome" -> CustomEnchantments.tome(CustomEnchantments.CRAB_CLAW, 1);
         case "curseofundying", "curse_undying", "undying" -> CustomEnchantments.tome(CustomEnchantments.CURSE_UNDYING, 1);
         case "flare", "flaretome" -> CustomEnchantments.tome(CustomEnchantments.FLARE, 1);
         case "guard", "guardtome" -> CustomEnchantments.tome(CustomEnchantments.GUARD, 1);
         case "handyman", "handymantome" -> CustomEnchantments.tome(CustomEnchantments.HANDYMAN, 1);
         case "hungeraspect", "hunger_aspect", "hungeraspecttome" -> CustomEnchantments.tome(CustomEnchantments.HUNGER_ASPECT, 1);
         case "moonwalk", "moonwalktome" -> CustomEnchantments.tome(CustomEnchantments.MOONWALK, 1);
         case "oceanheart", "ocean_heart", "oceanhearttome" -> CustomEnchantments.tome(CustomEnchantments.OCEAN_HEART, 1);
         case "rejuvenation", "rejuvenationtome" -> CustomEnchantments.tome(CustomEnchantments.REJUVENATION, 1);
         case "safelanding", "safe_landing", "safelandingtome" -> CustomEnchantments.tome(CustomEnchantments.SAFE_LANDING, 1);
         case "velocity", "velocitytome" -> CustomEnchantments.tome(CustomEnchantments.VELOCITY, 1);
         case "quickcharge", "quick_charge", "quickchargetome", "quick_charge_tome" -> CustomEnchantments.tome(CustomEnchantments.QUICK_CHARGE, 1);
         case "boltbringer", "bolt_bringer", "bolt bringer", "bolt", "boltbringertome" -> CCEnchantments.boltBringerBook(holders, 1);
         case "runefrost", "rune_frost", "runeoffrost", "frostrune" -> ModItems.runeOfFrost();
         case "runereach", "rune_reach", "runeofreach", "reachrune" -> ModItems.runeOfReach();
         case "runelifesteal", "rune_lifesteal", "runeoflifesteal", "lifestealrune" -> ModItems.runeOfLifesteal();
         case "runewarding", "rune_warding", "runeofwarding", "wardingrune" -> ModItems.runeOfWarding();
         case "runefortitude", "rune_fortitude", "runeoffortitude", "fortituderune" -> ModItems.runeOfFortitude();
         case "raidbanner", "raid_banner", "raidbanneritem", "banner" -> ModItems.raidBanner();
         case "warlordaxe", "warlord_axe" -> ModItems.warlordAxe();
         case "evokerspellbook", "evoker_spellbook", "espellbook" -> ModItems.evokerSpellbook();
         case "captainhorn", "captain_horn" -> ModItems.captainHorn();
         case "warlordcloak", "warlord_cloak" -> ModItems.warlordCloak();
         case "evokercloak", "evoker_cloak" -> ModItems.evokerCloak();
         case "illusionerspellbook", "illusioner_spellbook", "isellbook" -> ModItems.illusionerSpellbook();
         case "illusionercloak", "illusioner_cloak" -> ModItems.illusionerCloak();
         case "raidersupgrader", "raiders_item_upgrader", "itemupgrader" -> ModItems.raidersItemUpgrader();
         case "warlordtrophy", "warlord_trophy" -> ModItems.warlordTrophy();
         case "backpack", "bag" -> ModItems.backpack(1);
         case "bundle" -> ModItems.bundle(1);
         case "enderpouch", "ender_pouch" -> ModItems.enderPouch();
         case "distantmemoryshard", "distant_memory_shard", "memoryshard" -> ModItems.distantMemoryShard();
         case "distantmemory", "distant_memory_sword", "memorysword" -> ModItems.distantMemorySword();
         case "lastremembrance", "last_remembrance", "remembrance", "monarchblade" -> ModItems.lastRemembrance(
            holders
         );
         // One factory for Excalibur, so the command, the duel roll and the art
         // can never drift apart (see ModItems.excalibur).
         case "excalibur" -> ModItems.excalibur();
         case "goldenapplehead", "golden_apple_head", "gahead" -> {
            ItemStack s = new ItemStack(Items.GOLDEN_APPLE, 1);
            s.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lGolden Apple Head"));
            s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§eLucky PvP reward."))));
            yield s;
         }
         case "orbital", "orbitalstrike", "orbital_strike", "nuke", "nukeshot" -> nukeRod();
         case "stab", "stabstrike", "stab_strike", "drill", "stabshot" -> stabRod();
         case "law", "lawnuke", "law_nuke", "law-nuke", "lawnukeshot" -> lawNukeRod();
         case "withernuke", "wither_nuke", "wither", "withernukershot" -> witherNukeRod();
         case "wolfrod", "wolf_rod", "wolf", "wolfcannon", "wolf_cannon" -> wolfRod();
         case "seismictome", "seismic_tome", "seismic" -> CustomEnchantments.tome("ff_seismic", 1);
         case "overclocktome", "overclock_tome", "overclock" -> CustomEnchantments.tome(CustomEnchantments.OVERCLOCK, 1);
         case "starfalltome", "starfall_tome", "starfall" -> CustomEnchantments.tome(CustomEnchantments.STARFALL, 1);
         case "voidrendtome", "voidrend_tome", "voidrend" -> CustomEnchantments.tome(CustomEnchantments.VOIDREND, 1);
         case "levytome", "levy_tome", "levy", "sovereignlevy" -> CustomEnchantments.tome(CustomEnchantments.LEVY, 1);
         case "astraltome", "astral_tome", "astral" -> CustomEnchantments.tome(CustomEnchantments.ASTRAL, 1);
         case "mindwracktome", "mind_wrack_tome", "mindwrack" -> CustomEnchantments.tome("ff_mindwrack", 1);
         case "frostbitetome", "frostbite_tome", "frostbite" -> CustomEnchantments.tome("ff_frostbite", 1);
         case "lifestealtome", "lifesteal_tome", "lifesteal" -> CustomEnchantments.tome("ff_lifesteal", 1);
         case "stickytome", "sticky_tome", "sticky" -> CustomEnchantments.tome("ff_sticky", 1);
         case "commonkey", "common_mystery_key", "mysterykey" -> com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(0);
         case "rarekey", "rare_mystery_key" -> com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(1);
         case "epickey", "epic_mystery_key" -> com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(2);
         case "legendarykey", "legendary_mystery_key" -> com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(3);
         default -> null;
      };
   }

   private static ItemStack excaliburItem() {
      return ModItems.excalibur();
   }

   private static ItemStack nukeRod() {
      ItemStack s = new ItemStack(Items.FISHING_ROD, 1);
      s.set(DataComponents.CUSTOM_NAME, Component.literal("Nuke Shot"));
      s.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§c§l☄ Nuke Shot"),
               Component.literal("§7Casts a devastating orbital bombardment."),
               Component.literal("§7Throw it in the Overworld to fire."),
               Component.literal("§81 use before it breaks.")
            )
         )
      );
      s.set(DataComponents.DAMAGE, 60);
      s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      CompoundTag tag = new CompoundTag();
      tag.putInt("Orbital_Cannon", 1010);
      s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      return s;
   }

   private static ItemStack stabRod() {
      ItemStack s = new ItemStack(Items.FISHING_ROD, 1);
      s.set(DataComponents.CUSTOM_NAME, Component.literal("Stab Shot"));
      s.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§5§l☠ Stab Shot"),
               Component.literal("§7Fires a vertical column that drills straight down."),
               Component.literal("§7Throw it in the Overworld to fire."),
               Component.literal("§81 use before it breaks.")
            )
         )
      );
      s.set(DataComponents.DAMAGE, 60);
      s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      CompoundTag tag = new CompoundTag();
      tag.putInt("Orbital_Cannon", 1010);
      s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      return s;
   }

   private static ItemStack lawNukeRod() {
      ItemStack s = new ItemStack(Items.FISHING_ROD, 1);
      s.set(DataComponents.CUSTOM_NAME, Component.literal("Law-Nuke Shot"));
      s.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§a§lLaw-Nuke Shot"),
               Component.literal("§7Wide circular TNT bombardment."),
               Component.literal("§7Throw it in the Overworld to fire."),
               Component.literal("§81 use before it breaks.")
            )
         )
      );
      s.set(DataComponents.DAMAGE, 60);
      s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      CompoundTag tag = new CompoundTag();
      tag.putInt("Orbital_Cannon", 1010);
      s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      return s;
   }

   private static ItemStack witherNukeRod() {
      ItemStack s = new ItemStack(Items.FISHING_ROD, 1);
      s.set(DataComponents.CUSTOM_NAME, Component.literal("Wither Nuke Shot"));
      s.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§0§l☣ Wither Nuke Shot"),
               Component.literal("§7Unleashes a withering orbital strike."),
               Component.literal("§7Throw it in the Overworld to fire."),
               Component.literal("§81 use before it breaks.")
            )
         )
      );
      s.set(DataComponents.DAMAGE, 60);
      s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      CompoundTag tag = new CompoundTag();
      tag.putInt("Orbital_Cannon", 1010);
      s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      return s;
   }

   public static ItemStack stabShotRod() {
      ItemStack s = new ItemStack(Items.FISHING_ROD, 1);
      s.set(DataComponents.DAMAGE, 60);
      s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      s.set(DataComponents.CUSTOM_NAME, Component.literal("Stab Shot"));
      CompoundTag tag = new CompoundTag();
      tag.putInt("Orbital_Cannon", 1010);
      s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      return s;
   }

   private static ItemStack wolfRod() {
      ItemStack s = new ItemStack(Items.FISHING_ROD, 1);
      s.set(DataComponents.CUSTOM_NAME, Component.literal("Wolf Rod"));
      s.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§4§lADMIN ONLY"),
               Component.literal("§7Summons an army of wolves at the target."),
               Component.literal("§7Datapack-driven — requires the datapack to be loaded."),
               Component.literal("§81 use before it breaks.")
            )
         )
      );
      s.set(DataComponents.DAMAGE, 60);
      s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      CompoundTag tag = new CompoundTag();
      tag.putInt("wolf_cannon", 1010);
      s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      return s;
   }

   public static ItemStack[] allModItems(net.minecraft.core.HolderLookup.Provider holders) {
      List<ItemStack> out = new java.util.ArrayList<>();
      out.add(ModItems.chunkClaimer());
         out.add(ModItems.buySign());
         out.add(ModItems.sellSign());
         out.add(ModItems.autoSellHopper());
         out.add(ModItems.upwardsHopper());
         out.add(ModItems.elevator());
         out.add(ModItems.tokenRedeemer());
         out.add(ModItems.spawnerInfuser());
         out.add(ModItems.itemForge());
         out.add(ModItems.chunkAnchor());
         out.add(ModItems.repairStation());
         out.add(ModItems.itemSorter());
         out.add(ModItems.sorterTag());
         out.add(ModItems.superHopper());
         out.add(ModItems.transferHopper());
         out.add(ModItems.overflowHopper());
         out.add(ModItems.checkerHopper());
         out.add(ModItems.twoWaySplitter());
         out.add(ModItems.superSmelter());
         out.add(ModItems.portableFurnace());
         out.add(ModItems.portableCampfire());
         out.add(ModItems.potionBelt());
         out.add(ModItems.expeditionCompass());
         out.add(ModItems.autoPlanter());
         out.add(ModItems.autoHarvester());
         out.add(ModItems.irrigationSprinkler());
         out.add(ModItems.chair());
         out.add(ModItems.deathCompass());
         out.add(ModItems.bountyCompass());
         out.add(ModItems.sculkFood());
         out.add(ModItems.wormholePotion());
         out.add(ModItems.mysteryBox());
         out.add(ModItems.raidBossToken());
         out.add(ModItems.kingLootBox());
         out.add(ModItems.witherLootBox());
         out.add(ModItems.raidLootBox());
         out.add(ModItems.slimeLootBox());
         out.add(ModItems.witherStaff());
         out.add(ModItems.multidimensionalArmy());
         out.add(ModItems.witherBlade());
         out.add(ModItems.witherCrown());
         out.add(ModItems.witherCloakSword());
         out.add(ModItems.kingBone());
         out.add(ModItems.slimeBossToken());
         out.add(ModItems.slimeLauncher());
         out.add(ModItems.slimeShield());
         out.add(ModItems.slimeBoots());
         out.add(ModItems.slimeCore());
         out.add(ModItems.stoneGolemToken());
         out.add(ModItems.golemCore());
         out.add(ModItems.stoneStaff());
         out.add(ModItems.golemFist());
         out.add(ModItems.stoneHeart());
         out.add(ModItems.golemLootBox());
         out.add(ModItems.mindbinderEye());
         out.add(ModItems.shatteredMind());
         out.add(ModItems.mindbinderStaff());
         out.add(ModItems.possessedMask());
         out.add(ModItems.mindbinderShroud());
         out.add(ModItems.mindLootBox());
         out.add(ModItems.snowQueenToken());
         out.add(ModItems.snowLootBox());
         out.add(ModItems.iceStaff());
         out.add(ModItems.frozenHeart());
         out.add(ModItems.frostboundCrown());
         out.add(ModItems.glacierCloak());
         out.add(ModItems.sculkMedallion());
         out.add(ModItems.sculkLootBox());
         out.add(ModItems.sculkMageStaff());
         out.add(ModItems.sculkSensorLeggings());
         out.add(ModItems.wardensCall());
         out.add(ModItems.sculkEssence());
         out.add(ModItems.distantMemoryShard());
         out.add(ModItems.distantMemorySword());
         out.add(ModItems.lastRemembrance(holders));
         out.add(ModItems.runeOfHaste());
         out.add(ModItems.runeOfFlame());
         out.add(ModItems.runeOfFortune());
         out.add(ModItems.runeOfSwiftness());
         out.add(ModItems.runeOfFrost());
         out.add(ModItems.runeOfReach());
         out.add(ModItems.runeOfLifesteal());
         out.add(ModItems.runeOfWarding());
         out.add(ModItems.runeOfFortitude());
         out.add(ModItems.raidBanner());
         out.add(ModItems.warlordAxe());
         out.add(ModItems.evokerSpellbook());
         out.add(ModItems.captainHorn());
         out.add(ModItems.warlordCloak());
         out.add(ModItems.evokerCloak());
         out.add(ModItems.illusionerSpellbook());
         out.add(ModItems.illusionerCloak());
         out.add(ModItems.raidersItemUpgrader());
         out.add(ModItems.warlordTrophy());
         out.add(ModItems.backpack(1));
         out.add(ModItems.bundle(1));
         out.add(ModItems.enderPouch());
         out.add(ModItems.repairMembrane());
         out.add(com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(0));
         out.add(com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(1));
         out.add(com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(2));
         out.add(com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(3));
         out.add(ModItems.spaceTimeRift());
         out.add(ModItems.timeLordLootBox());
         out.add(ModItems.woodenMarionette());
         out.add(ModItems.puppeteerLootBox());
         out.add(ModItems.puppeteersMask());
         out.add(ModItems.marionetteStrings());
         out.add(ModItems.emptyMask());
         out.add(ModItems.pocketWatch(1));
         out.add(ModItems.pocketWatch(2));
         out.add(ModItems.chronoShard());
         out.add(ModItems.hourglassOfHaste());
         out.add(ModItems.scarletBlood());
         out.add(ModItems.scarletLootBox());
         out.add(ModItems.scarletTrophy());
         out.add(ModItems.bloodsoakedCore());
         out.add(ModItems.scarletFang());
         out.add(ModItems.scarletGrimoire());
         out.add(ModItems.bloodPrism());
         out.add(ModItems.clockworkCore());
         out.add(ModItems.clockworkLootBox());
         out.add(ModItems.clockworkTrophy());
         out.add(ModItems.mechScrap());
         out.add(ModItems.clockworkGauntlet());
         out.add(ModItems.mechanicalHeart());
         out.add(ModItems.automatonArmor());
         out.add(ModItems.astralCompass());
         out.add(ModItems.starboundLootBox());
         out.add(ModItems.starboundTrophy());
         out.add(ModItems.magicalEssence());
         out.add(ModItems.starpiercer());
         out.add(ModItems.astralMantle());
         out.add(ModItems.magistersCodex());
         out.add(ModItems.voidAnchor());
         out.add(ModItems.voidshaperLootBox());
         out.add(ModItems.colossusTrophy());
         out.add(ModItems.voidsteelScrap());
         out.add(ModItems.voidReaver());
         out.add(ModItems.colossusPlate());
         out.add(ModItems.shapingSigil());
         out.add(ModItems.sovereignsCrown());
         out.add(ModItems.sovereignLootBox());
         out.add(ModItems.sovereignTrophy());
         out.add(ModItems.royalTribute());
         out.add(ModItems.royalContract());
         out.add(ModItems.sovereignsBell());
         out.add(ModItems.emeraldSeal());
         out.add(ModItems.heartOfTheEnd());
         out.add(ModItems.dragonScale());
         out.add(ModItems.voidfang());
         out.add(ModItems.starfall());
         out.add(ModItems.enderheart());
         // The awakened tier, in the catalog as well as in the give switch: the use-coverage
         // check reads its handler table against this list, so a type that is handled but
         // never produced here is reported as a rename somebody forgot to finish.
         out.add(ModItems.voidfangAwakened());
         out.add(ModItems.starfallAwakened());
         out.add(ModItems.enderheartAwakened());
         // The sea and the sky: two summons, two boxes, the six legendaries and the two forge
         // materials their sets are upgraded with.
         out.add(ModItems.sovereignsHeart());
         out.add(ModItems.abyssalPearl());
         out.add(ModItems.galeCore());
         out.add(ModItems.drownedLootBox());
         out.add(ModItems.leviathansGrasp());
         out.add(ModItems.tidecaller());
         out.add(ModItems.abyssalChain());
         out.add(ModItems.galeSigil());
         out.add(ModItems.galeLootBox());
         out.add(ModItems.skybreaker());
         out.add(ModItems.galeChakram());
         out.add(ModItems.wardensMantle());
         out.add(com.fortuneandfavors.duel.DuelManager.goldenAppleHead(1));
         out.add(excaliburItem());
         out.add(nukeRod());
         out.add(stabRod());
         out.add(lawNukeRod());
         out.add(witherNukeRod());
         out.add(wolfRod());
      // Custom enchant tomes, using each enchantment's actual max level.
      for (String key : CustomEnchantments.ALL_KEYS) {
         for (int lvl = 1; lvl <= CustomEnchantments.maxLevelOf(key); lvl++) {
            out.add(CustomEnchantments.tome(key, lvl));
         }
      }
      // CCEnchantments tomes (creative-menu books): levels up to each enchant's
      // own max (Chainfire/Sniper/Revolver/Berserker are single-level).
      for (String key : CCEnchantments.ALL_KEYS) {
         for (int lvl = 1; lvl <= CCEnchantments.maxLevelOf(key); lvl++) {
            out.add(ccTome(key, lvl));
         }
      }
      // Bolt Bringer - a real (registry) enchantment, levels I-III.
      for (int lvl = 1; lvl <= 3; lvl++) {
         out.add(CCEnchantments.boltBringerBook(holders, lvl));
      }
      return out.toArray(new ItemStack[0]);
   }

   /** Resolves the CCEnchantments key stored on a ccTome book, or null. */
   private static String ccTomeKey(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return null;
      }
      CustomData custom = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (custom == null) {
         return null;
      }
      CompoundTag tag = custom.copyTag();
      for (String key : new String[]{
         CCEnchantments.CHAINFIRE, CCEnchantments.SNIPER, CCEnchantments.REVOLVER,
         CCEnchantments.SHARPSHOOTER, CCEnchantments.RECOIL, CCEnchantments.HUNTERS_MARK,
         CCEnchantments.HEAVY_BOLT, CCEnchantments.CURSE_FRAGILITY, CCEnchantments.COMBO,
         CCEnchantments.BERSERKER
      }) {
         if (tag.getInt(key).orElse(0) > 0) {
            return key;
         }
      }
      return null;
   }

   /** Builds a CCEnchantments tome (enchant book) with a level, a description,
    *  and the fuse hint in the lore. */
   private static ItemStack ccTome(String key, int level) {
      ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
      CCEnchantments.applyLevel(stack, key, level);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(CCEnchantments.coloredName(key) + " " + CCEnchantments.roman(level)));
      ItemLore lore = (ItemLore)stack.get(DataComponents.LORE);
      List<Component> lines = new ArrayList<>(lore != null ? lore.lines() : List.of());
      lines.add(Component.literal("§7" + CCEnchantments.description(key, level)));
      lines.add(Component.literal("§8Fuse in the Item Forge or on an anvil."));
      stack.set(DataComponents.LORE, new ItemLore(lines));
      return stack;
   }

   private static int toggleConfigFeature(CommandContext<CommandSourceStack> ctx, String feature) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (!feature.equals("welcome") && !feature.equals("laugh")) {
         boolean admin = ((CommandSourceStack)ctx.getSource()).permissions().hasPermission(Permissions.COMMANDS_ADMIN)
            || p != null && PermissionManager.isEconomyAdmin(p.getUUID());
         if (!admin) {
            ((CommandSourceStack)ctx.getSource())
               .sendFailure(Component.literal("Only an admin can toggle server features. You can use /ff config welcome or /ff config laugh for yourself."));
            return 0;
         } else if (!ModConfig.isFeature(feature) && !ModConfig.isExtendedToggle(feature)) {
            ((CommandSourceStack)ctx.getSource())
               .sendSuccess(
                  () -> Component.literal(
                     Chat.colorize(
                        "&cUnknown feature. Try: " + String.join(", ", ModConfig.FEATURES) + " or " + String.join(", ", ModConfig.EXTENDED_TOGGLES)
                     )
                  ),
                  false
               );
            return 0;
         } else if (ModConfig.isExtendedToggle(feature)) {
            // Settings that are not simple feature gates (the wither rework, the
            // boss dialogue, blast rebuild, the raid cooldown) still have to be
            // reachable by command so a server can be scripted without a GUI.
            String label = ModConfig.extendedDisplayName(feature);
            boolean now = ModConfig.toggleExtended(feature);
            ModConfig.save(((CommandSourceStack)ctx.getSource()).getServer());
            ((CommandSourceStack)ctx.getSource())
               .sendSuccess(() -> Component.literal(Chat.colorize("&a" + label + (now ? " &aenabled" : " &cdisabled") + "&a.")), true);
            return 1;
         } else {
            boolean now = ModConfig.toggle(feature);
            ModConfig.save(((CommandSourceStack)ctx.getSource()).getServer());
            // Tags are rendered in the tab list - apply the flip immediately
            // instead of waiting for the next periodic refresh.
            if (feature.equals("tags")) {
               TagManager.refreshTabList(((CommandSourceStack)ctx.getSource()).getServer());
            }
            ((CommandSourceStack)ctx.getSource())
               .sendSuccess(() -> Component.literal(Chat.colorize("&a" + ModConfig.displayName(feature) + (now ? " &aenabled" : " &cdisabled") + "&a.")), true);
            return 1;
         }
      } else if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This toggle must be run by a player."));
         return 0;
      } else {
         boolean now = ModConfig.togglePlayerPref(p.getUUID(), feature);
         ModConfig.save(((CommandSourceStack)ctx.getSource()).getServer());
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(() -> Component.literal(Chat.colorize("&a" + featureName(feature) + (now ? " &cturned off" : " &aturned on") + "&a for you.")), true);
         return 1;
      }
   }

   private static String featureName(String feature) {
      return switch (feature) {
         case "welcome" -> "First-join welcome";
         case "laugh" -> "Mindbinder laugh broadcasts";
         default -> feature;
      };
   }

   private static ChestShop lookedAtShop(ServerPlayer player) {
      BlockPos pos = lookedAtShopPos(player);
      return pos == null ? null : ChestShopManager.get(player.level(), pos);
   }

   /** Where /chestshop's ray trace landed, so delete can resolve the shop's own
    *  key (including the other half of a double chest) rather than guessing. */
   private static BlockPos lookedAtShopPos(ServerPlayer player) {
      return player.pick(5.0, 0.0F, false) instanceof BlockHitResult blockHit ? blockHit.getBlockPos() : null;
   }

   private static String normalizeCurrency(String currency) {
      if (currency.equalsIgnoreCase("cash")) {
         return "cash";
      }

      Identifier id = Identifier.tryParse(currency.contains(":") ? currency : "minecraft:" + currency);
      if (id == null) {
         return null;
      }

      Item item = (Item)BuiltInRegistries.ITEM.getValue(id);
      return item == Items.AIR ? null : id.toString();
   }

   private static String itemId(Item item) {
      return BuiltInRegistries.ITEM.getKey(item).toString();
   }

   private static int niceKeepInventoryToggle(CommandContext<CommandSourceStack> ctx) {
      boolean now = !NiceKeepInventoryManager.isEnabled();
      NiceKeepInventoryManager.setEnabled(now);
      ModConfig.save(((CommandSourceStack)ctx.getSource()).getServer());
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
               Chat.colorize(
                  now
                     ? "&aNice Keep Inventory &aenabled. &7On death, armor + tools stay with you, everything else goes into a grave (your head) at your last touched block."
                     : "&cNice Keep Inventory &cdisabled."
               )
            ),
            false
         );
      return 1;
   }

   private static int niceKeepInventorySet(CommandContext<CommandSourceStack> ctx, boolean on) {
      NiceKeepInventoryManager.setEnabled(on);
      ModConfig.save(((CommandSourceStack)ctx.getSource()).getServer());
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
               Chat.colorize(
                  on
                     ? "&aNice Keep Inventory enabled. &7On death, armor + tools stay with you, everything else goes into a grave (your head) at your last touched block."
                     : "&cNice Keep Inventory disabled."
               )
            ),
            false
         );
      return 1;
   }

   private static int niceKeepInventoryStatus(CommandContext<CommandSourceStack> ctx) {
      boolean on = NiceKeepInventoryManager.isEnabled();
      boolean claimOther = ModConfig.niceKeepInventoryAllowOthersClaim();
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
               Chat.colorize(
                  "&6&lNice Keep Inventory &r"
                     + (on ? "&aON" : "&cOFF")
                     + "\n&7  Admins can claim graves: "
                     + (claimOther ? "&aON" : "&cOFF")
                     + "\n&7  &e/NiceKeepInventory on|off&7 to toggle\n&7  &e/NiceKeepInventory claimother on|off&7 to toggle admin grave claiming"
               )
            ),
            false
         );
      return 1;
   }

   private static int niceKeepInventoryClaimOther(CommandContext<CommandSourceStack> ctx, Boolean force) {
      if (force == null) {
         boolean on = ModConfig.niceKeepInventoryAllowOthersClaim();
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(
               () -> Component.literal(
                  Chat.colorize(
                     "&6&lGrave claim rules &r"
                        + (on ? "&aadmins may claim anyone's grave" : "&4only the owner may claim")
                        + "\n&7Change it with &f/NiceKeepInventory claimother on&7 or &f off&7."
                  )
               ),
               false
            );
         return 1;
      }
      boolean now = force;
      ModConfig.setNiceKeepInventoryAllowOthersClaim(now);
      ModConfig.save(((CommandSourceStack)ctx.getSource()).getServer());
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
               Chat.colorize(now ? "&aAdmins can now claim other players' graves." : "&cAdmin grave claiming disabled - only the owner can claim.")
            ),
            false
         );
      return 1;
   }
}
