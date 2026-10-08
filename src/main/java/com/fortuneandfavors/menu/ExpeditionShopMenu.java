package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.ExpeditionProgression;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
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
 * The Expedition Shop: the permanent one.
 *
 * <p>Every other counter in this mod is inside something - the supplier's window only exists during
 * a run, and the descent menu only matters at the moment somebody is deciding to go in. This one is
 * open whether or not the player is standing in a site, because what it sells is not for a run: it
 * is what the <i>runs</i> have added up to. EXP is earned from what a run pays out (see
 * {@link ExpeditionProgression#expFor}) and spent here, permanently.
 *
 * <p>Every line, laid out as the tree its prerequisites describe: each slot shows the line's level as
 * the stack count, what the line is worth, and what the next level costs. The layout is computed
 * rather than hand-placed - {@link #nodeSlot} reads each line's depth down the tree and its column
 * across it from the same {@code prerequisite} field the purchase rule reads - so a branch added to
 * {@link ExpeditionProgression.Upgrade} is drawn under its parent with no code written here, and the
 * picture can never disagree with the gate. A finishing guide book sits beside Close and explains
 * the survivability branch in words. Nothing about a price is stored on the shelf - the menu reads
 * every number off the ledger when it draws - so a level bought in one window is a level the next
 * window opens with.
 *
 * <p>It is opened by {@code /expedition shop}, from the slot in the descent window, and by the
 * Expedition Broker when one is standing in a chamber. All three are the same window on purpose: a
 * shop with two inventories is a shop that can disagree with itself.
 */
public class ExpeditionShopMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int CLOSE = 49;
   /**
    * The guide book, in the footer row beside Close: the tree's own explanation, one click away from
    * the tree itself. See {@link ExpeditionGuideMenu}.
    */
   private static final int GUIDE = 46;
   /**
    * The first usable cell of the tree: row 1, column 1. Nothing about the tree's shape is written
    * here - {@link #nodeSlot} lays every line out from its own prerequisite, so a branch added to
    * {@link ExpeditionProgression.Upgrade} is drawn under its parent with no code in this file.
    */
   private static final int SHELF_FIRST = 10;
   /** Columns the window's frame leaves usable on one row: the first and last are the frame. */
   private static final int COLUMNS = 7;
   /** Rows the window's frame leaves usable: the header and footer rows are not part of the tree. */
   private static final int ROWS = 4;

   private final SimpleContainer container;
   private final ServerPlayer owner;

   public ExpeditionShopMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54));
   }

   private ExpeditionShopMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      if (player == null) {
         return;
      }
      player.openMenu(new SimpleMenuProvider(
         (syncId, inv, p) -> new ExpeditionShopMenu(syncId, inv),
         Component.literal("§6§lExpedition Shop")
      ));
   }

   /** Every line the shelf carries, as icons - for the harness, so the tree cannot go unlisted. */
   public static List<ItemStack> listedIcons() {
      List<ItemStack> out = new ArrayList<>();
      for (ExpeditionProgression.Upgrade line : ExpeditionProgression.Upgrade.values()) {
         out.add(new ItemStack(line.icon));
      }
      return out;
   }

   /**
    * How deep the tree goes: the longest chain of prerequisites, read off the tree itself.
    *
    * <p>The one number the layout below needs that is not a line's own position, and it is derived
    * rather than written down - a branch added three deep changes this with no code in this file.
    */
   private static final int MAX_DEPTH = depthOf();
   /**
    * Rows between a line and its child.
    *
    * <p>Two when the window can spare the row, so the connector pane has somewhere to sit and the
    * branch reads as hanging off its parent; one when the tree is deep enough that a spare row on
    * every edge would run it into the footer. Either way it is the whole file's one layout knob.
    */
   private static final int ROW_STEP = 1 + MAX_DEPTH * 2 <= ROWS ? 2 : 1;
   /**
    * Every line's column, assigned once by walking the tree.
    *
    * <p>A node sits over the leftmost leaf of its own subtree, which is what makes the columns come
    * out distinct at every depth without a collision pass: two nodes at the same depth belong to
    * disjoint subtrees, so their leaf ranges - and therefore their columns - cannot overlap. Wide
    * trees wrap a node to the row below rather than colliding with it, which is the one concession
    * to a tree larger than the window.
    */
   private static final java.util.Map<ExpeditionProgression.Upgrade, Integer> COLUMN = columns();

   /**
    * Where one line is drawn: its depth down the tree, and its column across it.
    *
    * <p>Position is derived from {@link ExpeditionProgression.Upgrade#prerequisite}, the same field
    * the purchase rule reads, so the picture and the gate cannot disagree - a line that hangs off
    * another is drawn hanging off it, and a future branch is drawn correctly without a line of
    * layout code being written for it.
    */
   private static int nodeSlot(ExpeditionProgression.Upgrade line) {
      int row = 1 + line.depth() * ROW_STEP + COLUMN.getOrDefault(line, 0) / COLUMNS;
      return row * 9 + 1 + (COLUMN.getOrDefault(line, 0) % COLUMNS);
   }

   /** The pane drawn between a line and its parent - the visible edge of the branch, or -1. */
   private static int connectorSlot(ExpeditionProgression.Upgrade line) {
      if (line.prerequisite == null || ROW_STEP < 2) {
         return -1;
      }
      return nodeSlot(line) - 9;
   }

   /** Test seam: where a line is drawn, so the harness can assert the tree really is a tree. */
   public static int slotOf(ExpeditionProgression.Upgrade line) {
      return nodeSlot(line);
   }

   /** Test seam: the connector drawn between a branch and its parent, or -1 for a root line. */
   public static int connectorOf(ExpeditionProgression.Upgrade line) {
      return connectorSlot(line);
   }

   /** The longest chain of prerequisites in the tree. */
   private static int depthOf() {
      int deepest = 0;
      for (ExpeditionProgression.Upgrade line : ExpeditionProgression.Upgrade.values()) {
         deepest = Math.max(deepest, line.depth());
      }
      return deepest;
   }

   /** The lines that hang off one parent, in declaration order. */
   private static List<ExpeditionProgression.Upgrade> childrenOf(ExpeditionProgression.Upgrade parent) {
      List<ExpeditionProgression.Upgrade> out = new ArrayList<>();
      for (ExpeditionProgression.Upgrade line : ExpeditionProgression.Upgrade.values()) {
         if (line.prerequisite == parent) {
            out.add(line);
         }
      }
      return out;
   }

   /** Assigns every line its column by walking each root's subtree left to right. */
   private static java.util.Map<ExpeditionProgression.Upgrade, Integer> columns() {
      java.util.EnumMap<ExpeditionProgression.Upgrade, Integer> out =
         new java.util.EnumMap<>(ExpeditionProgression.Upgrade.class);
      int[] next = {0};
      for (ExpeditionProgression.Upgrade line : ExpeditionProgression.Upgrade.values()) {
         if (line.prerequisite == null) {
            placeColumn(line, out, next);
         }
      }
      return out;
   }

   /** One subtree's columns: a leaf takes the next free column, a parent the leftmost of its own. */
   private static int placeColumn(
      ExpeditionProgression.Upgrade line,
      java.util.EnumMap<ExpeditionProgression.Upgrade, Integer> out,
      int[] next
   ) {
      List<ExpeditionProgression.Upgrade> children = childrenOf(line);
      int column;
      if (children.isEmpty()) {
         column = next[0]++;
      } else {
         column = Integer.MAX_VALUE;
         for (ExpeditionProgression.Upgrade child : children) {
            column = Math.min(column, placeColumn(child, out, next));
         }
      }
      out.put(line, column);
      return column;
   }

   /** One line, drawn at this player's level: the stack count is the level, the lore is the offer. */
   private ItemStack line(ExpeditionProgression.Upgrade upgrade) {
      int level = ExpeditionProgression.level(this.owner.getUUID(), upgrade);
      long cost = ExpeditionProgression.nextCost(this.owner.getUUID(), upgrade);
      long banked = ExpeditionProgression.available(this.owner.getUUID());
      boolean locked = upgrade.prerequisite != null
         && ExpeditionProgression.level(this.owner.getUUID(), upgrade.prerequisite) <= 0;
      ItemStack stack = new ItemStack(upgrade.icon);
      // The count IS the level. A level-1 line is a single icon, a maxed one is a full stack, and a
      // bare "1" on an unbought line would read as a purchase - so an unbought line shows a level
      // pip list instead and its count is left at one.
      stack.setCount(Math.max(1, Math.min(upgrade.maxLevel, level)));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(upgrade.colour + "§l" + upgrade.name));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal(upgrade.blurb));
      lore.add(Component.literal(pips(level, upgrade.maxLevel)));
      lore.add(Component.literal(upgrade.detail));
      if (locked) {
         // The tree's gate said out loud: a locked line names the line above it, so the row order is
         // never a mystery the player has to solve by price.
         lore.add(Component.literal("§8Hangs off §7" + upgrade.prerequisite.name));
         lore.add(Component.literal("§cLocked - buy §f" + upgrade.prerequisite.name + "§c first."));
      } else if (cost < 0L) {
         lore.add(Component.literal("§a§lMAXED"));
      } else {
         lore.add(Component.literal("§7Next level: §f" + cost + " EXP"));
         lore.add(Component.literal(
            banked >= cost ? "§aClick to buy." : "§cYou need §f" + (cost - banked) + " EXP§c more."
         ));
      }
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   /** The edge between a branch and its parent - a pane that names the line it hangs off. */
   private ItemStack connector(ExpeditionProgression.Upgrade line) {
      ItemStack pane = new ItemStack(Items.STAINED_GLASS_PANE.lime());
      pane.set(DataComponents.CUSTOM_NAME,
         Component.literal("§8│ §7hangs off §f" + line.prerequisite.name));
      pane.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§8This line opens once the one above is bought.")
      )));
      return pane;
   }

   /** The level as a row of filled and empty pips, so the ladder is readable at a glance. */
   private static String pips(int level, int max) {
      StringBuilder sb = new StringBuilder("§8Level §f").append(level).append("§8/§7").append(max).append("  ");
      for (int i = 0; i < max; i++) {
         sb.append(i < level ? "§a\u25A0" : "§8\u25A1");
      }
      return sb.toString();
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 6, Items.STAINED_GLASS_PANE.orange());
      for (ExpeditionProgression.Upgrade line : ExpeditionProgression.Upgrade.values()) {
         int connector = connectorSlot(line);
         if (connector >= 0) {
            this.container.setItem(connector, this.connector(line));
         }
         this.container.setItem(nodeSlot(line), this.line(line));
      }
      this.container.setItem(INFO, this.info());
      this.container.setItem(GUIDE, this.guideCard());
      ItemStack close = new ItemStack(Items.BARRIER);
      close.set(DataComponents.CUSTOM_NAME, Component.literal("§cClose"));
      this.container.setItem(CLOSE, close);
      this.broadcastChanges();
   }

   private ItemStack info() {
      long available = ExpeditionProgression.available(this.owner.getUUID());
      long earned = ExpeditionProgression.earned(this.owner.getUUID());
      long spent = Math.max(0L, earned - available);
      ItemStack info = new ItemStack(Items.EXPERIENCE_BOTTLE);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lExpedition EXP"));
      info.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Everything a run pays out earns EXP -"),
         Component.literal("§7one point per §f" + Chat.moneyStr(ExpeditionProgression.MONEY_PER_EXP) + "§7 secured."),
         Component.literal(""),
         Component.literal("§aAvailable: §f" + available + " EXP"),
         Component.literal("§7Spent: §f" + spent + " EXP"),
         Component.literal("§8Earned all-time: §7" + earned + " EXP"),
         Component.literal(""),
         Component.literal("§8Upgrades are permanent and apply inside sites.")
      )));
      return info;
   }

   /** The way to the tree's own explanation, so a player who cannot read the gate can go and read. */
   private ItemStack guideCard() {
      ItemStack book = new ItemStack(Items.KNOWLEDGE_BOOK);
      book.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lThe Survival Tree"));
      book.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7What the branches are, what each"),
         Component.literal("§7level buys, and why a line can be"),
         Component.literal("§7locked behind the one it hangs off."),
         Component.literal("§eClick to read")
      )));
      return book;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      if (slotId == CLOSE) {
         sp.closeContainer();
         return;
      }
      if (slotId == GUIDE) {
         ExpeditionGuideMenu.open(sp);
         return;
      }
      for (ExpeditionProgression.Upgrade line : ExpeditionProgression.Upgrade.values()) {
         if (nodeSlot(line) == slotId) {
            this.buy(sp, line);
            return;
         }
      }
      this.broadcastChanges();
   }

   private void buy(ServerPlayer sp, ExpeditionProgression.Upgrade line) {
      String error = ExpeditionProgression.buy(sp.getUUID(), line);
      if (error != null) {
         Chat.msg(sp, error);
         SoundUtil.play(sp, ModSounds.DENY);
         this.broadcastChanges();
         return;
      }
      int level = ExpeditionProgression.level(sp.getUUID(), line);
      SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
      Chat.raw(sp, "§a§l" + line.name.toUpperCase() + " §r§7is now level §f" + level + "§7. "
         + "§8(" + ExpeditionProgression.available(sp.getUUID()) + " EXP left)");
      this.rebuild();
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
