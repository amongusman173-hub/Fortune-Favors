package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.PrisonCellblock;
import com.fortuneandfavors.economy.PrisonManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The prison control panel. One board that follows you between the mine, the
 * combat arena, the fishing pond and the hole, and surfaces the whole Cell Block
 * loop: your Heat and the guards it brings, the break-out status, and the three
 * contracts on the noticeboard.
 */
public class PrisonMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int ZONE_ACTION = 10;
   private static final int EXECUTION = 11;
   private static final int HEAT = 12;
   private static final int REFRESH = 13;
   private static final int GUARDS = 14;
   private static final int HEAT_TOOL = 15;
   private static final int ESCAPE = 16;
   private static final int TRAVEL_BACK = 19;
   private static final int RANK_UP = 21;
   private static final int PICK_UPGRADE = 23;
   private static final int CONVERT = 25;
   private static final int COMBAT = 28;
   private static final int PROCESSING = 29;
   private static final int FISH = 30;
   private static final int TOKENS = 31;
   private static final int CELL = 32;
   private static final int TRUST = 33;
   private static final int LEAVE = 34;
   private static final int CONTRACT_A = 37;
   private static final int CONTRACT_B = 39;
   private static final int CONTRACT_C = 41;
   private static final int REROLL = 43;
   private static final int CLOSE = 49;

   /** What the exchange charges, in prison tokens. */
   public static final long TOKEN_GEM_COST = 10L;
   public static final long TOKEN_KEY_COST = 25L;
   /** The two cooling tools, in tokens: the cheap rig and the strong coolant. */
   public static final long TOKEN_RIG_COST = 12L;
   public static final long TOKEN_COOLANT_COST = 30L;

   private final SimpleContainer container;
   private final ServerPlayer owner;

   public PrisonMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54));
   }

   private PrisonMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new PrisonMenu(syncId, inv), Component.literal("§c§lCell Block")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 6, Items.STAINED_GLASS_PANE.red());
      int rank = PrisonManager.rankOf(this.owner.getUUID());
      long cash = PrisonManager.balanceOf(this.owner.getUUID());
      java.util.UUID id = this.owner.getUUID();
      boolean solitary = PrisonManager.isConfined(this.owner);

      this.container.setItem(INFO, this.infoCard(rank, cash));

      // ---- Heat row ----
      int h = PrisonCellblock.heatOf(id);
      ItemStack heatStack = new ItemStack(Items.BLAZE_POWDER);
      heatStack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lHeat"));
      java.util.List<Component> heatLore = new java.util.ArrayList<>();
      // The tier's own name and its own sentence about what the prison is doing, rather than a
      // bar and a threshold the prisoner has to remember: see PrisonCellblock.Alert.
      heatLore.add(Component.literal(PrisonCellblock.alertLine(id)));
      for (PrisonCellblock.Alert alert : PrisonCellblock.Alert.values()) {
         boolean here = PrisonCellblock.alertOf(h) == alert;
         heatLore.add(Component.literal(
            (here ? "§f\u25B8 " : "§8  ") + alert.colour + alert.label + " §8" + alert.from + "§8+"
               + (here ? " §7- " + alert.doing : "")
         ));
      }
      PrisonManager.Sector sector = PrisonManager.sectorOf(PrisonManager.rankOf(id));
      heatLore.add(Component.literal(
         "§8" + sector.name + " pays §f" + String.format("%.1f", sector.heat) + "×(Heat)"
      ));
      heatLore.add(Component.literal(
         "§8Your cell bleeds a point off every §f" + String.format("%.1f", PrisonManager.cellCoolingTicks(id) / 20.0) + "s§8."
      ));
      heatLore.add(Component.literal("§8Selling a haul launders a big chunk of it."));
      heatStack.set(DataComponents.LORE, new ItemLore(heatLore));
      this.container.setItem(HEAT, heatStack);

      // ---- Refresh Blocks: the mine no longer refills itself on a timer (a block coming back in
      // front of a prisoner, and over the glowstone landing at that, is the bug the timer caused),
      // so this is how a prisoner asks for a fresh face without walking off the floor they work.
      ItemStack refreshStack = new ItemStack(Items.STONE_PICKAXE);
      refreshStack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lRefresh Blocks"));
      refreshStack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Puts your own seam back to full."),
               Component.literal("§8The mine resets when a floor is left or entered;"),
               Component.literal("§8this is the button for right now.")
            )
         )
      );
      this.container.setItem(REFRESH, refreshStack);

      // ---- Processing: where the restricted stone becomes cash, and the only place it does.
      long carried = PrisonManager.contrabandValue(this.owner);
      ItemStack processStack = new ItemStack(Items.LODESTONE);
      processStack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lProcessing"));
      List<Component> processLore = new java.util.ArrayList<>();
      processLore.add(Component.literal("§7The till buys wages; this pad buys §econtraband§7."));
      processLore.add(Component.literal(PrisonManager.processingReadout(this.owner)));
      if (carried <= 0L) {
         processLore.add(Component.literal("§8Nothing restricted in your bag right now."));
      } else {
         processLore.add(Component.literal("§7Pays at declaration, minus the scanner's share,"));
         processLore.add(Component.literal("§7plus §dtokens§7 - and it launders Heat."));
      }
      processLore.add(Component.literal("§8Click to walk to the pad."));
      processStack.set(DataComponents.LORE, new ItemLore(processLore));
      this.container.setItem(PROCESSING, processStack);

      // ---- The exchange: the only sink for tokens, so the currency is not a scoreboard.
      long tokens = PrisonManager.tokensOf(id);
      ItemStack tokenStack = new ItemStack(Items.AMETHYST_SHARD);
      tokenStack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lPrison Tokens: " + tokens));
      tokenStack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Earned by §fdeclaring hauls§7 at Processing,"),
               Component.literal("§7never by selling at the till."),
               Component.literal(""),
               Component.literal("§fLeft-click§7: §d" + TOKEN_GEM_COST + " tokens §7→ §b1 gem"),
               Component.literal("§fRight-click§7: §d" + TOKEN_KEY_COST + " tokens §7→ §bCommon Mystery Key"),
               Component.literal("§8Tokens buy nothing outside the block.")
            )
         )
      );
      this.container.setItem(TOKENS, tokenStack);

      // ---- Heat tools: the other way down the Heat meter, for a prisoner who has dug themselves
      // hot and does not want to walk to the till with a guard already on them. A carried tool
      // bleeds Heat a point at a time; see PrisonCellblock.toolCoolTicks.
      ItemStack toolStack = new ItemStack(Items.BLUE_ICE);
      toolStack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lHeat Tools"));
      java.util.List<Component> toolLore = new java.util.ArrayList<>();
      String carriedTool = PrisonCellblock.toolLine(this.owner);
      toolLore.add(Component.literal(carriedTool == null
         ? "§7Carry a tool and your Heat bleeds off §fon its own§7."
         : carriedTool));
      toolLore.add(Component.literal(""));
      toolLore.add(Component.literal("§fLeft-click§7: §d" + TOKEN_RIG_COST + " tokens §7→ §bCooling Rig"));
      toolLore.add(Component.literal("§8  -1 Heat every " + (PrisonCellblock.RIG_COOL_TICKS / 20) + "s while carried."));
      toolLore.add(Component.literal("§fRight-click§7: §d" + TOKEN_COOLANT_COST + " tokens §7→ §bContraband Coolant"));
      toolLore.add(Component.literal("§8  -1 Heat every " + (PrisonCellblock.COOLANT_COOL_TICKS / 20) + "s while carried."));
      toolStack.set(DataComponents.LORE, new ItemLore(toolLore));
      this.container.setItem(HEAT_TOOL, toolStack);

      // ---- Your cell: the only purchase in here that is about what happens when you stop.
      int cell = PrisonManager.cellLevelOf(id);
      ItemStack cellStack = new ItemStack((net.minecraft.world.item.Item)Items.BED.pick(net.minecraft.world.item.DyeColor.RED));
      cellStack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lYour Cell"));
      java.util.List<Component> cellLore = new java.util.ArrayList<>();
      cellLore.add(Component.literal("§f" + PrisonManager.cellName(cell) + "§8 (rung " + cell + "/" + PrisonManager.CELL_MAX + ")"));
      cellLore.add(Component.literal(
         "§7Heat cools a point every §f" + String.format("%.1f", PrisonManager.cellCoolingTicks(id) / 20.0) + "s§7."
      ));
      long cellCost = PrisonManager.cellUpgradeCost(cell);
      if (cellCost < 0L) {
         cellLore.add(Component.literal("§8Nothing better to move into."));
      } else {
         int after = PrisonManager.cellCoolingTicksAt(cell + 1);
         cellLore.add(Component.literal("§7Next: §f" + PrisonManager.cellName(cell + 1) + "§7 - every §f" + String.format("%.1f", after / 20.0) + "s"));
         cellLore.add(Component.literal("§7Cost: §e$" + String.format("%,d", cellCost)));
         cellLore.add(Component.literal("§8Buy it and the guards forget you sooner."));
      }
      cellStack.set(DataComponents.LORE, new ItemLore(cellLore));
      this.container.setItem(CELL, cellStack);

      // ---- Trust: the second ladder next to the cell. The cell buys back the cooling rate; trust
      // buys the gain, so a trusted prisoner can work the same seam longer before it bites.
      int trust = PrisonManager.trustLevelOf(id);
      ItemStack trustStack = new ItemStack(Items.NAME_TAG);
      trustStack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lTrust"));
      java.util.List<Component> trustLore = new java.util.ArrayList<>();
      trustLore.add(Component.literal("§f" + PrisonManager.trustName(trust) + "§8 (rung " + trust + "/" + PrisonManager.TRUST_MAX + ")"));
      trustLore.add(Component.literal(
         "§7Heat from every block: §f" + Math.round(PrisonManager.trustHeatMult(id) * 100.0) + "%§7 of normal."
      ));
      long trustCost = PrisonManager.trustUpgradeCost(trust);
      if (trustCost < 0L) {
         trustLore.add(Component.literal("§8The block trusts you as far as it can."));
      } else {
         int after = trust + 1;
         double afterMult = 1.0 - PrisonManager.TRUST_PERCENT_PER_LEVEL * after / 100.0;
         trustLore.add(Component.literal("§7Next: §f" + PrisonManager.trustName(after) + "§7 - §f" + Math.round(afterMult * 100.0) + "%§7 of the burn."));
         trustLore.add(Component.literal("§7Cost: §e$" + String.format("%,d", trustCost)));
         trustLore.add(Component.literal("§8Buy it and the same seam costs you less."));
      }
      trustStack.set(DataComponents.LORE, new ItemLore(trustLore));
      this.container.setItem(TRUST, trustStack);

      ItemStack guardStack = new ItemStack(Items.IRON_AXE);
      guardStack.set(DataComponents.CUSTOM_NAME, Component.literal("§4§lCell Block Guards"));
      int alive = PrisonCellblock.guardCount(id);
      List<Component> guardLore = new java.util.ArrayList<>();
      guardLore.add(Component.literal("§7Hunting you: §f" + alive));
      guardLore.add(Component.literal("§7Bounty: §e$" + String.format("%,d", 120L + 18L * rank)));
      guardLore.add(Component.literal("§7Putting one down drops your Heat by §a15§7."));
      // The manhunt's own man, named where a prisoner will read it rather than discovered.
      guardLore.add(Component.literal("§c§lThe Warden§r §7at Heat §c" + PrisonCellblock.MANHUNT + "§7:"));
      guardLore.add(Component.literal("§7Bounty: §e$" + String.format("%,d", PrisonCellblock.wardenReward(rank)) + " §7and a §fclean record§7."));
      guardLore.add(Component.literal("§8If they put you down, you wake up in your cell - never die."));
      // The search ladder, said where a prisoner will read it. This is the state the guards act on,
      // and it is the one thing about the capture rules a player cannot see from the mine: being
      // cuffed once changes what every guard does to them for the rest of the run.
      int breakouts = PrisonCellblock.breakoutsOf(id);
      if (PrisonCellblock.isSearched(id)) {
         guardLore.add(Component.literal(""));
         guardLore.add(Component.literal("§c§lSEARCHED PRISONER"));
         guardLore.add(Component.literal(
            "§7Cuffs broken: §f" + breakouts + "§7. They have stopped arresting you."
         ));
         long window = PrisonCellblock.shakedownSecondsLeft(id);
         if (window > 0L) {
            guardLore.add(Component.literal(
               "§6§lRUN WINDOW §8| §f" + window + "s§7 - nothing can be taken from you."
            ));
         } else {
            guardLore.add(Component.literal("§7Every hit now costs §f5 stacks§7 off your bag."));
            guardLore.add(Component.literal("§8Each hit buys you a few seconds to break away."));
         }
      }
      guardStack.set(DataComponents.LORE, new ItemLore(guardLore));
      this.container.setItem(GUARDS, guardStack);

      ItemStack escapeStack = new ItemStack(Items.CRACKED_STONE_BRICKS);
      escapeStack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lBreak-Out"));
      if (PrisonCellblock.isEscaping(id)) {
         escapeStack.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§c§lESCAPE IN PROGRESS"),
            Component.literal(PrisonCellblock.hasEscapeKey(id)
               ? "§aKey in hand - the barred gate will open."
               : "§7The §4Warden §7is carrying the gate key."),
            Component.literal("§7Put him down, then reach the exit before the timer."),
            Component.literal("§7Reaching it wipes your Heat and pays a jackpot.")
         )));
      } else {
         escapeStack.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Keep mining - a §floose brick§7 sometimes"),
            Component.literal("§7works free of the wall."),
            Component.literal("§7Break it to start a break-out."),
            Component.literal("§7The exit is behind a §fbarred gate§7: the"),
            Component.literal("§4Warden §7who runs the tunnel holds the key."),
            Component.literal("§8No Warden, no key? Nine iron bars, and"),
            Component.literal("§8an iron pickaxe, and the clock."),
            Component.literal("§7Jackpot: §e$" + String.format("%,d", PrisonCellblock.escapeJackpot(rank)))
         )));
      }
      this.container.setItem(ESCAPE, escapeStack);

      // The chair, said before anything else about the zone: an execution is the one state where
      // the prisoner's whole job is to get out of the room, so the tile is the countdown and the
      // three ways out rather than a verb.
      if (PrisonCellblock.isInExecution(id)) {
         ItemStack chair = new ItemStack(Items.REDSTONE_BLOCK);
         chair.set(DataComponents.CUSTOM_NAME, Component.literal(
            PrisonCellblock.inChair(id) ? "§4§lTHE CHAIR" : "§4§lMAXIMUM SECURITY"
         ));
         chair.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Out in §f" + PrisonCellblock.executionSecondsLeft(id) + "s§7 - then you are thrown out."),
            Component.literal("§7Losing it costs your §fhaul§7, the block, and a §f2m§7 lock-out."),
            Component.literal(""),
            Component.literal("§aTwo ways out:"),
            Component.literal("§f▪ Vent §7- dig the gravel out of the cell floor and get into the duct"),
            Component.literal("§8   before the patrol they send reaches your cell."),
            Component.literal("§f▪ Bars §7- mine your cell gate; two bars calls five guards.")
         )));
         this.container.setItem(EXECUTION, chair);
      }

      if (PrisonCellblock.isCuffed(id)) {
         List<Integer> locks = PrisonCellblock.cuffLocks(id);
         boolean hard = PrisonCellblock.cuffIsHard(id);
         ItemStack bound = new ItemStack(Items.IRON_BARS);
         bound.set(DataComponents.CUSTOM_NAME, Component.literal("§4§l" + (hard ? "THE NETHERITE SHACKLE" : "IN CUFFS")));
         List<Component> boundLore = new java.util.ArrayList<>();
         boundLore.add(Component.literal("§7Lock §f" + (PrisonCellblock.cuffStep(id) + 1) + "§7/§f"
            + (locks == null ? 0 : locks.size()) + " §8| §7" + PrisonCellblock.cuffSecondsLeft(id) + "s left."));
         if (hard) {
            boundLore.add(Component.literal("§4The Warden's own shackle: one second a lock."));
         }
         boundLore.add(Component.literal("§7Click the §elit lock§7 in the Cuffs window."));
         boundLore.add(Component.literal("§7The guards hold fire while you work them."));
         boundLore.add(Component.literal(hard
            ? "§8Miss the clock and it is the hole, and everything on you."
            : "§8Miss the clock and it is your cell, and everything on you."));
         bound.set(DataComponents.LORE, new ItemLore(boundLore));
         this.container.setItem(ZONE_ACTION, bound);
      } else if (solitary) {
         boolean inCell = PrisonCellblock.isInCell(id);
         ItemStack locked = new ItemStack(Items.IRON_BARS);
         locked.set(DataComponents.CUSTOM_NAME, Component.literal(
            inCell ? "§4§lYOUR CELL" : "§4§lSOLITARY CONFINEMENT"
         ));
         locked.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Out in §f" + PrisonCellblock.solitarySecondsLeft(id) + "s§7."),
            Component.literal(inCell
               ? "§7You were put down. Your haul was taken; your gear is safe."
               : "§7Everything you were carrying was confiscated."),
            Component.literal(inCell
               ? "§8Upgrading your cell changes where this happens."
               : "§8Selling your haul is the quiet way to avoid this.")
         )));
         this.container.setItem(ZONE_ACTION, locked);
      } else if (PrisonManager.isInArena(this.owner)) {
         int swordLvl = PrisonManager.swordLevelOf(id);
         ItemStack sword = new ItemStack(swordLvl >= 2 ? Items.NETHERITE_SWORD : swordLvl >= 1 ? Items.DIAMOND_SWORD : Items.IRON_SWORD);
         sword.set(DataComponents.CUSTOM_NAME, Component.literal(
            swordLvl >= PrisonManager.SWORD_MAX
               ? "§8Sword Tier " + (swordLvl + 1) + " - Maxed!"
               : "§c§lUpgrade Sword (Tier " + (swordLvl + 1) + " → " + (swordLvl + 2) + ")"
         ));
         sword.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal(swordLvl >= PrisonManager.SWORD_MAX
               ? "§7Netherite · Sharpness III"
               : "§7Cost: §e$" + String.format("%,d", PrisonManager.swordUpgradeCost(swordLvl))),
            Component.literal("§7Sharpness " + (1 + swordLvl) + " → " + (2 + swordLvl)),
            Component.literal("§7Never breaks in prison.")
         )));
         this.container.setItem(ZONE_ACTION, sword);
      } else if (PrisonManager.isInPond(this.owner)) {
         ItemStack rod = new ItemStack(Items.FISHING_ROD);
         rod.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lGet Fishing Rod"));
         rod.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Lure III · Luck of the Sea III · Unbreaking III"),
            Component.literal("§7Every catch pays §e$30-$80§7 prison cash."),
            Component.literal("§81-in-20 treasure catches pay 5x.")
         )));
         this.container.setItem(ZONE_ACTION, rod);
      } else {
         ItemStack sell = new ItemStack(Items.GOLD_INGOT);
         sell.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lSell All Blocks"));
         sell.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Sell every mine block in your inventory"),
            Component.literal("§7for prison cash at prison prices."),
            Component.literal("§7Also launders Heat and pays the Sell contract.")
         )));
         this.container.setItem(ZONE_ACTION, sell);
      }

      // ---- Progression row ----
      if (PrisonManager.isInSideZone(this.owner) || PrisonManager.isOnArenaApron(this.owner)) {
         this.container.setItem(TRAVEL_BACK, this.simple(Items.IRON_PICKAXE, "§e§lReturn to Mine",
            "§7Teleport back to your ore floors."));
      }
      ItemStack rankUp = new ItemStack(Items.DIAMOND);
      rankUp.set(DataComponents.CUSTOM_NAME, Component.literal(
         rank >= PrisonManager.RANKS - 1 ? "§8Rank Z - Maxed!" : "§a§lRank Up to " + PrisonManager.rankName(rank + 1)
      ));
      rankUp.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal(rank >= PrisonManager.RANKS - 1
            ? "§7You're at the top of the block."
            : "§7Cost: §e$" + String.format("%,d", PrisonManager.rankUpCost(rank))),
         Component.literal(rank >= PrisonManager.RANKS - 1
            ? "§7Bragging rights!"
            : "§7Unlocks a richer mine tier and a better pickaxe.")
      )));
      this.container.setItem(RANK_UP, rankUp);

      int pickLvl = PrisonManager.pickLevelOf(id);
      ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
      pick.set(DataComponents.CUSTOM_NAME, Component.literal(
         pickLvl >= PrisonManager.PICK_MAX
            ? "§8Pickaxe Lv " + pickLvl + " - Maxed!"
            : "§b§lUpgrade Pickaxe (Lv " + pickLvl + " → " + (pickLvl + 1) + ")"
      ));
      int eff = Math.min(5, 1 + pickLvl);
      int fort = Math.min(3, Math.max(0, pickLvl - 2));
      pick.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal(pickLvl >= PrisonManager.PICK_MAX
            ? "§7Maxed! Eff V · Fortune III"
            : "§7Cost: §e$" + String.format("%,d", PrisonManager.pickUpgradeCost(pickLvl))),
         Component.literal("§7Current: Eff " + eff + (fort > 0 ? " · Fortune " + fort : "")),
         Component.literal("§7Never breaks.")
      )));
      this.container.setItem(PICK_UPGRADE, pick);

      this.container.setItem(CONVERT, this.simple(Items.EMERALD, "§a§lConvert to Main Cash",
         "§7Prison cash → main cash at " + PrisonManager.CONVERT_RATE + ":1.",
         "§8Balance: §e$" + String.format("%,d", cash)));

      // ---- Travel row ----
      if (!PrisonManager.isInArena(this.owner) && !solitary) {
         PrisonManager.PitMode live = PrisonManager.pitModeOf(id);
         ItemStack combat = new ItemStack(Items.IRON_SWORD);
         combat.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lThe Prison Pit"));
         List<Component> pitLore = new java.util.ArrayList<>();
         pitLore.add(Component.literal(live == null
            ? "§7Five fights on one floor, below the block:"
            : "§cMid-fight: §f" + live.label + " §7round §f" + PrisonManager.pitRoundOf(id) + "§7/§f" + live.rounds + "§7."));
         pitLore.add(Component.literal("§71v1 · Gauntlet · Bounty · Warden · Champion."));
         pitLore.add(Component.literal("§7Each pays §ecash§7, §dtokens§7 and a run bonus."));
         pitLore.add(Component.literal("§7Leaving the Pit abandons the run - no payout."));
         pitLore.add(Component.literal("§8Click to choose a fight."));
         combat.set(DataComponents.LORE, new ItemLore(pitLore));
         this.container.setItem(COMBAT, combat);
      }
      if (!PrisonManager.isInPond(this.owner) && !solitary) {
         ItemStack fish = new ItemStack(Items.FISHING_ROD);
         fish.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lFishing Pond"));
         fish.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7A 9x9 pond beside the arena with an enchanted rod."),
            Component.literal("§7Catch fish for §e$30-$80§7 prison cash each."),
            Component.literal("§81-in-20 treasure catches pay 5x!")
         )));
         this.container.setItem(FISH, fish);
      }
      this.container.setItem(LEAVE, this.simple(Items.ENDER_PEARL, "§bLeave Prison",
         "§7Return to where you were standing in the overworld.",
         "§7Your own gear comes back out of the stash."));

      // ---- Contract row ----
      List<PrisonCellblock.Contract> list = PrisonCellblock.contractsOf(id);
      this.container.setItem(CONTRACT_A, this.contractCard(list, 0));
      this.container.setItem(CONTRACT_B, this.contractCard(list, 1));
      this.container.setItem(CONTRACT_C, this.contractCard(list, 2));
      ItemStack reroll = new ItemStack(Items.PAPER);
      reroll.set(DataComponents.CUSTOM_NAME, Component.literal("§f§lReroll Contracts"));
      reroll.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Tear up the noticeboard and pin up"),
         Component.literal("§7three new contracts."),
         Component.literal("§7Cost: §e$" + String.format("%,d", PrisonCellblock.rerollCost(id)))
      )));
      this.container.setItem(REROLL, reroll);

      this.container.setItem(CLOSE, this.simple(Items.BARRIER, "§cClose"));
      this.broadcastChanges();
   }

   private ItemStack contractCard(List<PrisonCellblock.Contract> list, int index) {
      if (index >= list.size()) {
         return new ItemStack(Items.AIR);
      }
      PrisonCellblock.Contract c = list.get(index);
      ItemStack stack = new ItemStack(switch (c.kind) {
         case MINE -> Items.IRON_PICKAXE;
         case SLAY -> Items.IRON_SWORD;
         case GUARDS -> Items.IRON_AXE;
         case SELL -> Items.GOLD_INGOT;
         case ESCAPE -> Items.CRACKED_STONE_BRICKS;
      });
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(
         (c.done() ? "§a§l✔ " : "§e§l") + c.kind.label + (c.done() ? " §7- ready!" : "")
      ));
      List<Component> lore = new java.util.ArrayList<>();
      lore.add(Component.literal("§7Progress: §f" + c.progressText() + " " + bar(c.progress, c.target)));
      lore.add(Component.literal("§7Reward: §e$" + String.format("%,d", c.reward)));
      lore.add(Component.literal(c.done() ? "§aClick to claim." : "§8Keep working."));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private static String bar(int progress, int target) {
      int filled = target <= 0 ? 10 : Math.min(10, progress * 10 / target);
      StringBuilder sb = new StringBuilder("§8[");
      for (int i = 0; i < 10; i++) {
         sb.append(i < filled ? "§a❚" : "§8❚");
      }
      return sb.append("§8]").toString();
   }

   private ItemStack infoCard(int rank, long cash) {
      java.util.UUID id = this.owner.getUUID();
      ItemStack info = new ItemStack(Items.NETHER_STAR);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lTHE CELL BLOCK"));
      List<Component> lore = new java.util.ArrayList<>();
      lore.add(Component.literal("§7Rank: §f" + PrisonManager.rankName(rank) + "§7 (" + (rank + 1) + "/" + PrisonManager.RANKS + ") §8· Tier " + (PrisonManager.tierOf(rank) + 1)));
      lore.add(Component.literal("§7Trustee rate: §a+" + PrisonManager.trusteePercent(rank) + "%§7 on everything you sell."));
      lore.add(Component.literal("§7Prison cash: §e$" + String.format("%,d", cash)));
      lore.add(Component.literal("§7Heat: " + PrisonCellblock.heatBar(id)));
      lore.add(Component.literal("§7Guards on you: §f" + PrisonCellblock.guardCount(id)));
      int done = 0;
      for (PrisonCellblock.Contract c : PrisonCellblock.contractsOf(id)) {
         if (c.done()) {
            done++;
         }
      }
      lore.add(Component.literal("§7Contracts ready: §a" + done));
      // The block's clock, on the card that already answers "what is happening right now".
      // The block's clock, not the level's game time: a dimension that is not the overworld never
      // advances its game time, so a menu drawn from it announced an event the payouts were not in.
      lore.add(Component.literal(com.fortuneandfavors.economy.PrisonEvents.statusLine(
         com.fortuneandfavors.economy.PrisonCellblock.clock(this.owner.level())
      )));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§8Mine ore for cash - but the richer the ore,"));
      lore.add(Component.literal("§8the more Heat you build. Sell to launder it."));
      info.set(DataComponents.LORE, new ItemLore(lore));
      return info;
   }

   private ItemStack simple(net.minecraft.world.item.Item item, String name, String... loreLines) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (loreLines != null && loreLines.length > 0) {
         List<Component> lore = new java.util.ArrayList<>();
         for (String line : loreLines) {
            if (line != null) {
               lore.add(Component.literal(line));
            }
         }
         stack.set(DataComponents.LORE, new ItemLore(lore));
      }
      return stack;
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      try {
         if (slotId == CLOSE || slotId == LEAVE) {
            this.returnCarried(sp);
            sp.closeContainer();
            if (slotId == LEAVE) {
               PrisonManager.leave(sp);
            }
            return;
         }
         if (slotId == ZONE_ACTION) {
            if (PrisonCellblock.isCuffed(sp.getUUID())) {
               // The struggle was closed at some point; it is still on, so put it back in front of
               // them rather than leaving them to be walked to the hole with nothing to click.
               this.returnCarried(sp);
               CuffMenu.open(sp);
               return;
            }
            if (PrisonManager.isConfined(sp)) {
               // Locked up - nothing to do but wait.
            } else if (PrisonManager.isInArena(sp)) {
               this.run(sp, () -> PrisonManager.swordUpgrade(sp));
            } else if (PrisonManager.isInPond(sp)) {
               PrisonManager.giveFishingRod(sp);
               SoundUtil.play(sp, ModSounds.TRANSFER);
            } else {
               this.run(sp, () -> PrisonManager.sellAll(sp));
            }
            return;
         }
         if (slotId == REFRESH) {
            this.run(sp, () -> PrisonManager.refreshFloor(sp));
            return;
         }
         if (slotId == RANK_UP) {
            this.run(sp, () -> PrisonManager.rankUp(sp));
            return;
         }
         if (slotId == PICK_UPGRADE) {
            this.run(sp, () -> PrisonManager.pickUpgrade(sp));
            return;
         }
         if (slotId == CELL) {
            this.run(sp, () -> PrisonManager.cellUpgrade(sp));
            return;
         }
         if (slotId == TRUST) {
            this.run(sp, () -> PrisonManager.trustUpgrade(sp));
            return;
         }
         if (slotId == PROCESSING) {
            this.teleport(sp, () -> PrisonManager.gotoProcessing(sp));
            return;
         }
         if (slotId == TOKENS) {
            long cost = button == 1 ? TOKEN_KEY_COST : TOKEN_GEM_COST;
            String err = PrisonManager.spendTokens(sp, cost);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               SoundUtil.play(sp, ModSounds.DENY);
            } else if (button == 1) {
               com.fortuneandfavors.util.InventoryHelper.giveOrDrop(
                  sp, com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(
                     com.fortuneandfavors.economy.MysteryChestManager.COMMON)
               );
               Chat.msg(sp, "&d-" + cost + " tokens &7→ &bCommon Mystery Key&7.");
               SoundUtil.play(sp, ModSounds.CLAIM);
            } else {
               com.fortuneandfavors.economy.TokenManager.giveGems(sp, 1);
               Chat.msg(sp, "&d-" + cost + " tokens &7→ &b1 gem&7.");
               SoundUtil.play(sp, ModSounds.CLAIM);
            }
            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
            return;
         }
         if (slotId == HEAT_TOOL) {
            boolean strong = button == 1;
            long cost = strong ? TOKEN_COOLANT_COST : TOKEN_RIG_COST;
            String err = PrisonManager.spendTokens(sp, cost);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               SoundUtil.play(sp, ModSounds.DENY);
            } else {
               InventoryHelper.giveOrDrop(sp, strong ? PrisonCellblock.coolingCoolant() : PrisonCellblock.coolingRig());
               Chat.msg(sp, "&d-" + cost + " tokens &7→ &b" + (strong ? "Contraband Coolant" : "Cooling Rig") + "&7. Keep it on you and your Heat bleeds off.");
               SoundUtil.play(sp, ModSounds.CLAIM);
            }
            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
            return;
         }
         if (slotId == CONVERT) {
            this.run(sp, () -> PrisonManager.convertAll(sp));
            return;
         }
         if (slotId == TRAVEL_BACK) {
            this.teleport(sp, () -> PrisonManager.returnToMine(sp));
            return;
         }
         if (slotId == COMBAT) {
            this.returnCarried(sp);
            PitMenu.open(sp);
            return;
         }
         if (slotId == FISH) {
            this.teleport(sp, () -> PrisonManager.enterFishingPond(sp));
            return;
         }
         if (slotId >= CONTRACT_A && slotId <= CONTRACT_C) {
            int index = (slotId - CONTRACT_A) / 2;
            String err = PrisonCellblock.claimContract(sp, index);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               SoundUtil.play(sp, ModSounds.DENY);
            } else {
               SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
            }
            this.refresh(sp);
            return;
         }
         if (slotId == REROLL) {
            String err = PrisonCellblock.reroll(sp);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               SoundUtil.play(sp, ModSounds.DENY);
            } else {
               SoundUtil.play(sp, ModSounds.PAGE_FLIP);
            }
            this.refresh(sp);
            return;
         }
         if (slotId == HEAT || slotId == GUARDS || slotId == ESCAPE || slotId == INFO) {
            Chat.msg(sp, "&7Hover an item to read its details.");
         }
      } finally {
         this.returnCarried(sp);
      }
   }

   /** Runs an action that returns an error string (or null), then refreshes the board. */
   private void run(ServerPlayer sp, java.util.function.Supplier<String> action) {
      String err = action.get();
      if (err != null) {
         Chat.msg(sp, "&c" + err);
         SoundUtil.play(sp, ModSounds.DENY);
      } else {
         SoundUtil.play(sp, ModSounds.TRANSFER);
      }
      this.refresh(sp);
   }

   private void teleport(ServerPlayer sp, java.util.function.Supplier<String> action) {
      sp.closeContainer();
      String err = action.get();
      if (err != null) {
         Chat.msg(sp, "&c" + err);
         SoundUtil.play(sp, ModSounds.DENY);
      } else {
         SoundUtil.play(sp, ModSounds.TRANSFER);
      }
   }

   private void refresh(ServerPlayer sp) {
      this.rebuild();
      this.broadcastChanges();
      this.returnCarried(sp);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
