package com.fortuneandfavors.duel;

import com.fortuneandfavors.duel.DuelManager.BotDifficulty;
import com.fortuneandfavors.duel.DuelManager.CombatStyle;
import com.fortuneandfavors.duel.DuelManager.DuelMode;
import com.fortuneandfavors.menu.BotDifficultyMenu;
import com.fortuneandfavors.menu.DuelModeMenu;
import com.fortuneandfavors.menu.DuelStatsMenu;
import com.fortuneandfavors.menu.FFAModeMenu;
import com.fortuneandfavors.util.Chat;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class DuelCommands {
   private DuelCommands() {
   }

   public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                                                               "duel"
                                                            )
                                                            .executes(DuelCommands::usage))
                                                         .then(Commands.argument("player", EntityArgument.player()).executes(DuelCommands::challenge)))
                                                      .then(
                                                         ((LiteralArgumentBuilder)Commands.literal("bot").executes(ctx -> botChallenge(ctx, null)))
                                                            .then(
                                                               Commands.argument("difficulty", StringArgumentType.word())
                                                                  .executes(DuelCommands::botChallengeWithDifficulty)
                                                            )
                                                      ))
                                                   .then(Commands.literal("accept").executes(ctx -> answer(ctx, true))))
                                                .then(Commands.literal("deny").executes(ctx -> answer(ctx, false))))
                                             .then(Commands.literal("quit").executes(DuelCommands::quit)))
                                          .then(Commands.literal("cancel").executes(DuelCommands::quit)))
                                       .then(Commands.literal("rematch").executes(DuelCommands::rematch)))
                                    .then(Commands.literal("ffa").then(Commands.argument("mode", StringArgumentType.word()).executes(DuelCommands::ffa))))
                                 .then(Commands.literal("join").executes(DuelCommands::ffaJoin)))
                              .then(Commands.literal("start").executes(DuelCommands::ffaStart)))
                           .then(
                              ((LiteralArgumentBuilder)Commands.literal("vote").then(Commands.literal("legacy").executes(ctx -> vote(ctx, CombatStyle.LEGACY))))
                                 .then(Commands.literal("modern").executes(ctx -> vote(ctx, CombatStyle.MODERN)))
                           ))
                        .then(Commands.literal("stats").executes(DuelCommands::records)))
                     .then(Commands.literal("leaderboard").executes(DuelCommands::records)))
                  .then(
                     ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("wager")
                              .then(
                                 Commands.argument("player", EntityArgument.player())
                                    .then(Commands.argument("amount", StringArgumentType.word()).executes(ctx -> wagerCommand(ctx)))
                              ))
                           .then(Commands.literal("accept").executes(ctx -> wagerAccept(ctx))))
                        .then(Commands.literal("deny").executes(ctx -> wagerDeny(ctx)))
                  ))
               .then(Commands.literal("spectate").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> spectateCommand(ctx)))))
            .then(Commands.literal("unspectate").executes(ctx -> unspectateCommand(ctx)))
            .then(
               ((LiteralArgumentBuilder)Commands.literal("diag")
                     .requires(
                        source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN)
                     )
                     .executes(DuelCommands::diag))
                  .then(
                     ((LiteralArgumentBuilder)Commands.literal("probe")
                           .executes(ctx -> diagProbe(ctx, -1)))
                        .then(
                           Commands.argument("plot", StringArgumentType.word())
                              .executes(ctx -> diagProbe(ctx, parseIntOr(StringArgumentType.getString(ctx, "plot"), -1)))
                        )
                  )
            )
      );
      dispatcher.register((LiteralArgumentBuilder)Commands.literal("cancel").executes(DuelCommands::quit));
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("ffa")
                     .executes(DuelCommands::ffaGui))
                  .then(Commands.literal("join").executes(DuelCommands::ffaJoin)))
               .then(Commands.literal("start").executes(DuelCommands::ffaStart)))
            .then(Commands.argument("mode", StringArgumentType.word()).executes(DuelCommands::ffa))
      );
   }

   private static int usage(CommandContext<CommandSourceStack> ctx) {
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
               Chat.colorize(
                  "&6&lDuel Arena&r&7 - &e/duel <player>&7 to challenge (or &e/duel bot&7 to practice).\n&7  &e/duel bot <difficulty>&7   easy / normal / hard / train\n&7  &e/duel accept&7 / &e/duel deny&7   answer a challenge\n&7  &e/duel quit&7 / &e/cancel&7   forfeit (you keep your stuff)\n&7  &e/duel rematch&7   fight the same player/mode again\n&7  &e/ffa&7   open FFA mode picker (free-for-all)\n&7  &e/ffa join&7 / &e/ffa start&7   join/start an FFA\n&7  &e/duel vote legacy|modern&7   vote during a lobby\n&7  &e/duel wager <player> <amount>&7   challenge to a pool wager duel\n&7  &e/duel wager accept/deny&7   accept or decline a wager\n&7  &e/duel spectate <player>&7   watch someone's duel\n&7  &e/duel unspectate&7   stop watching\n&7  &e/duel stats&7 / &e/duel leaderboard&7   your record\n&7  &e/duel diag&7   dump the arena floor, block by block (admin)\n&7  &e/duel diag probe [plot]&7   compare that floor with your own client (admin)\n&7  &e/duel <player> [mode]&7   modes: modern, legacy, mace, crystal, uhc, axe, combo,\n&7      bedwars, skywars, lucky, kits, randomizer, draft, laststand, tntrun,\n&7      &asumo&7, &aarchery&7, &agladiator"
               )
            ),
            false
         );
      return 1;
   }

   private static int challenge(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }

      try {
         String name = EntityArgument.getPlayer(ctx, "player").getName().getString();
         String err = DuelManager.beginChallenge(p, name);
         if (err != null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
            return 0;
         } else {
            DuelModeMenu.open(p);
            return 1;
         }
      } catch (CommandSyntaxException e) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + e.getMessage()));
         return 0;
      }
   }

   private static int botChallenge(CommandContext<CommandSourceStack> ctx, BotDifficulty difficulty) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }

      if (difficulty != null) {
         String err = DuelManager.beginChallenge(p, "bot", difficulty);
         if (err != null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
            return 0;
         }

         DuelModeMenu.open(p);
      } else {
         BotDifficultyMenu.open(p);
      }

      return 1;
   }

   private static int botChallengeWithDifficulty(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String diffName = StringArgumentType.getString(ctx, "difficulty");
         // "custom" opens the builder instead of starting a match, because a custom
         // bot is not a name - it is a set of dials that has to be set first.
         if (diffName != null && diffName.equalsIgnoreCase("custom")) {
            com.fortuneandfavors.menu.CustomBotMenu.open(p);
            return 1;
         }

         BotDifficulty difficulty = BotDifficulty.byName(diffName);
         if (difficulty == null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§cUnknown difficulty: " + diffName + " (use easy, normal, hard, train, custom)"));
            return 0;
         } else {
            String err = DuelManager.beginChallenge(p, "bot", difficulty);
            if (err != null) {
               ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
               return 0;
            } else {
               DuelModeMenu.open(p);
               return 1;
            }
         }
      }
   }

   private static int answer(CommandContext<CommandSourceStack> ctx, boolean accept) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         failOrOk(ctx, accept ? DuelManager.accept(p) : DuelManager.deny(p));
         return 1;
      }
   }

   /**
    * Reports the live state of the duel system to an admin.
    *
    * <p>Written for the reports that are impossible to act on without it: "the arena
    * does not render", "I kept the wrong items", "I am stuck as a spectator". Each of
    * those has one of a handful of causes - a half-built arena, a plot that was never
    * released, a fighter with no saved state - and none of them is visible from the
    * outside. This prints the numbers instead of asking for a screenshot.
    */
   private static int diag(CommandContext<CommandSourceStack> ctx) {
      CommandSourceStack src = (CommandSourceStack)ctx.getSource();
      ServerPlayer p = src.getPlayer();
      if (p == null) {
         src.sendFailure(Component.literal("§cThis command must be run by a player."));
         return 0;
      }

      for (String line : DuelManager.diagnose(p)) {
         Chat.msg(p, line);
      }

      return 1;
   }

   /**
    * Asks the caller's own client what it holds for a duel arena floor.
    *
    * <p>The server cannot answer this for itself: "the arena was never built"
    * and "the arena was built and this client never received it" look identical
    * from the server's side, because both of them are merely a client that has
    * nothing to draw. Only the client knows which one it is, so the command
    * sends it the floor the server built and prints what comes back.
    */
   private static int diagProbe(CommandContext<CommandSourceStack> ctx, int plot) {
      CommandSourceStack src = (CommandSourceStack)ctx.getSource();
      ServerPlayer p = src.getPlayer();
      if (p == null) {
         src.sendFailure(Component.literal("§cThis command must be run by a player - it is your client that is asked."));
         return 0;
      }

      for (String line : DuelManager.probe(p, plot)) {
         Chat.msg(p, line);
      }

      return 1;
   }

   private static int parseIntOr(String s, int fallback) {
      try {
         return Integer.parseInt(s.trim());
      } catch (Exception e) {
         return fallback;
      }
   }

   private static int quit(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         failOrOk(ctx, DuelManager.quit(p));
         return 1;
      }
   }

   private static int rematch(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String err = DuelManager.rematch(p);
         if (err != null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
            return 0;
         } else {
            DuelModeMenu.open(p);
            return 1;
         }
      }
   }

   private static int vote(CommandContext<CommandSourceStack> ctx, CombatStyle style) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         failOrOk(ctx, DuelManager.vote(p, style));
         return 1;
      }
   }

   private static int kit(CommandContext<CommandSourceStack> ctx, String kit) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         failOrOk(ctx, DuelManager.setKit(p, kit));
         return 1;
      }
   }

   private static int records(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         DuelStatsMenu.open(p);
         return 1;
      }
   }

   private static int wagerCommand(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }

      ServerPlayer target;
      try {
         target = EntityArgument.getPlayer(ctx, "player");
      } catch (CommandSyntaxException e) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + e.getMessage()));
         return 0;
      }

      long amount;
      try {
         amount = Long.parseLong(StringArgumentType.getString(ctx, "amount"));
      } catch (NumberFormatException e) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§cAmount must be a number."));
         return 0;
      }

      String err = DuelManager.placeBet(p, target, amount);
      if (err != null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
         return 0;
      } else {
         Chat.msg(p, "&aWager challenge sent! &e" + Chat.moneyStr(amount) + " &7pool wager to &f" + target.getName().getString() + "&7.");
         return 1;
      }
   }

   private static int wagerAccept(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String err = DuelManager.acceptWager(p);
         if (err != null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
            return 0;
         } else {
            return 1;
         }
      }
   }

   private static int wagerDeny(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String err = DuelManager.denyWager(p);
         if (err != null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + err));
            return 0;
         } else {
            return 1;
         }
      }
   }

   private static int ffaGui(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         FFAModeMenu.open(p);
         return 1;
      }
   }

   private static int ffa(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }

      String modeName = StringArgumentType.getString(ctx, "mode");
      DuelMode mode = DuelMode.byName(modeName);
      if (mode == null) {
         mode = DuelMode.byName(modeName.replace("_", ""));
      }

      if (mode == null) {
         Chat.msg(p, "&cUnknown mode. Valid FFA modes: modern, legacy, luckypvp, tntrun, kits");
         return 0;
      } else if (!mode.supportsFfa()) {
         Chat.msg(p, "&c" + mode.display + " doesn't support FFA. Valid FFA modes: modern, legacy, luckypvp, tntrun, kits");
         return 0;
      } else {
         String err = DuelManager.requestFFA(p, mode);
         failOrOk(ctx, err);
         return 1;
      }
   }

   private static int ffaJoin(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String err = DuelManager.joinFFA(p);
         failOrOk(ctx, err);
         return 1;
      }
   }

   private static int ffaStart(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         String err = DuelManager.startFFA(p);
         failOrOk(ctx, err);
         return 1;
      }
   }

   private static void failOrOk(CommandContext<CommandSourceStack> ctx, String error) {
      if (error != null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + error));
      }
   }

   private static int spectateCommand(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      }

      ServerPlayer target;
      try {
         target = EntityArgument.getPlayer(ctx, "player");
      } catch (CommandSyntaxException e) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("§c" + e.getMessage()));
         return 0;
      }

      String err = DuelManager.spectate(p, target.getUUID());
      if (err != null) {
         Chat.msg(p, "§c" + err);
         return 0;
      } else {
         return 1;
      }
   }

   private static int unspectateCommand(CommandContext<CommandSourceStack> ctx) {
      ServerPlayer p = ((CommandSourceStack)ctx.getSource()).getPlayer();
      if (p == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("This command must be run by a player."));
         return 0;
      } else {
         DuelManager.unspectate(p);
         return 1;
      }
   }
}
