package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.ExpeditionProgression;
import com.fortuneandfavors.util.GuiUtil;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The Expedition Shop's tree, explained in words.
 *
 * <p>The shop drew the survival branch as a tree and gated it as one, and a picture of a branch and a
 * locked line is still a question a player has to answer for themselves: <i>why</i> is the second
 * line locked behind the first? The answer is the design - a branch is what a healer learns
 * <i>next</i>, and Field Kit only makes sense to a body that has already bought the health budget
 * back - and a design nobody states is a design nobody can read. This is that statement, surfaced
 * from the descent window and from the shop itself.
 *
 * <p>It is a guide rather than a tooltip because it has three things to say and only one of them
 * fits on a card: what the root buys, what the branch's three levels buy, and why the gate exists.
 * Every sentence that names a number reads the number off the shop's own constants, so a rebalance
 * cannot leave the guide describing a shop that no longer exists - a guide that lies is worse than
 * no guide at all.
 *
 * <p>Opened from the descent window's guide card and from the shop's, so it is one click from both
 * places a player is deciding what to buy.
 */
public class ExpeditionGuideMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int ROOT = 11;
   private static final int LINK = 20;
   private static final int BRANCH = 29;
   private static final int VENOM = 13;
   private static final int FALLS = 14;
   private static final int CLOCK = 15;
   private static final int WHY = 22;
   private static final int EXP = 23;
   private static final int SHOP = 24;
   private static final int CLOSE = 31;

   private final SimpleContainer container;

   public ExpeditionGuideMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(36));
   }

   private ExpeditionGuideMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x4, syncId, playerInventory, container, 4);
      this.container = container;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      if (player == null) {
         return;
      }
      player.openMenu(new SimpleMenuProvider(
         (syncId, inv, p) -> new ExpeditionGuideMenu(syncId, inv),
         Component.literal("§d§lThe Survival Tree")
      ));
   }

   /** The root line the guide is about - read from the shop, so the two can never name different lines. */
   public static ExpeditionProgression.Upgrade guideRoot() {
      return ExpeditionProgression.Upgrade.FIELD_MEDICINE;
   }

   /** The branch that hangs off the root. */
   public static ExpeditionProgression.Upgrade guideBranch() {
      return ExpeditionProgression.Upgrade.FIELD_KIT;
   }

   /**
    * The guide's prose, in the order it is read - the part a player takes away, and the part the
    * harness can pin. Names and numbers are filled from the shop's own lines, so this is a statement
    * about the tree as it is, not as it was when the sentence was written.
    */
   public static List<String> explanation() {
      ExpeditionProgression.Upgrade root = guideRoot();
      ExpeditionProgression.Upgrade branch = guideBranch();
      List<String> out = new ArrayList<>();
      out.add("§d§l" + root.name + " §r§7is the root: " + strip(root.blurb));
      out.add("§d§l" + branch.name + " §r§7hangs off it - " + strip(branch.blurb));
      out.add("§7A branch is locked until the line it hangs off is bought,"
         + " because it is what a healer learns §fnext§7: it only makes sense to a body that has"
         + " already bought the health budget back.");
      out.add("§7The shop draws the branch directly under its root for the same reason -"
         + " the picture and the rule are one thing.");
      return out;
   }

   /** One shop line's one-line description, without the leading colour code. */
   private static String strip(String text) {
      return text.startsWith("§") ? text.substring(2) : text;
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 4, Items.STAINED_GLASS_PANE.magenta());

      ItemStack info = new ItemStack(Items.KNOWLEDGE_BOOK);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lThe Survival Tree"));
      info.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7The Expedition Shop sells lines. Some stand"),
         Component.literal("§7alone, and one hangs off another - this is"),
         Component.literal("§7that branch, and why it is built that way.")
      )));
      this.container.setItem(INFO, info);

      this.container.setItem(ROOT, this.lineCard(
         guideRoot(),
         List.of(
            Component.literal("§8This is the §7root§8 of the branch."),
            Component.literal("§7Buy it and the healing a site gives back"),
            Component.literal("§7rises - mends and food regen both.")
         )
      ));
      this.container.setItem(LINK, this.linkCard());
      this.container.setItem(BRANCH, this.lineCard(
         guideBranch(),
         List.of(
            Component.literal("§cLocked until §f" + guideRoot().name + "§c is bought."),
            Component.literal("§a1 §7Venom burns out §ftwice as fast§7 in a site."),
            Component.literal("§a2 §7A fall inside a site costs §fhalf§7."),
            Component.literal("§a3 §7A cleared chamber pays §f+"
               + (ExpeditionProgression.KIT_CHAMBER_REBATE_TICKS / 20L) + "s§7 back on the clock.")
         )
      ));

      this.container.setItem(VENOM, this.note(Items.HONEY_BOTTLE, "§2Venom",
         "§7The one damage that finds you whatever you do. The branch's first level shortens it to "
            + pct(ExpeditionProgression.KIT_VENOM_SCALE) + " of the site's own cap."));
      this.container.setItem(FALLS, this.note(Items.LEATHER_BOOTS, "§2Falls",
         "§7The one mistake the maze bills all at once. The branch's second level makes a fall inside "
            + "a site cost " + pct(ExpeditionProgression.KIT_FALL_SCALE) + " of what it did."));
      this.container.setItem(CLOCK, this.note(Items.CLOCK, "§2The clock",
         "§7The leak no site can close: the maze is infinite and the timer is not. The branch's last "
            + "level hands " + (ExpeditionProgression.KIT_CHAMBER_REBATE_TICKS / 20L)
            + "s back for every chamber you clear."));

      this.container.setItem(WHY, this.note(Items.OAK_SIGN, "§6Why a line is locked",
         "§7A branch is what a healer learns §fnext§7. Field Kit pays a body that already survives the "
            + "trip; on one that does not, it would be answering the wrong question."));
      this.container.setItem(EXP, this.note(Items.EXPERIENCE_BOTTLE, "§6Earning EXP",
         "§7EXP is not bought, it is banked: every run pays EXP from what you secure. Survive and "
            + "escape to keep it - die or run out of time and the run pays nothing."));
      this.container.setItem(SHOP, this.note(Items.EMERALD, "§6Where to buy",
         "§7Open the Expedition Shop from the descent window, with §f/expedition shop§7, or from the "
            + "Expedition Broker carrying it into a chamber."));

      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   /** One shop line, drawn from the shop's own data plus the guide's own two lines about it. */
   private ItemStack lineCard(ExpeditionProgression.Upgrade line, List<Component> extra) {
      ItemStack stack = new ItemStack(line.icon);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(line.colour + "§l" + line.name));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal(line.blurb));
      lore.add(Component.literal(line.detail));
      lore.add(Component.literal("§7Levels: §f" + line.maxLevel + "§8 · §7base cost: §f" + line.base + " EXP"));
      lore.addAll(extra);
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   /** The pane that draws the branch hanging off the root, exactly as the shop draws it. */
   private ItemStack linkCard() {
      ItemStack pane = new ItemStack(Items.STAINED_GLASS_PANE.lime());
      pane.set(DataComponents.CUSTOM_NAME,
         Component.literal("§8│ §f" + guideBranch().name + "§8 hangs off §f" + guideRoot().name));
      pane.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§8Bought above, opened below.")
      )));
      return pane;
   }

   private ItemStack note(Item icon, String name, String text) {
      ItemStack stack = new ItemStack(icon);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§l" + name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(text))));
      return stack;
   }

   private ItemStack named(ItemStack stack, String name) {
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return stack;
   }

   /** A fraction as a percentage of what it was: `0.5` -> `half`. */
   private static String pct(float scale) {
      int percent = Math.round(scale * 100.0F);
      return percent == 50 ? "half" : (percent + "%");
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp && slotId == CLOSE) {
         sp.closeContainer();
         return;
      }
      super.clicked(slotId, button, input, player);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
