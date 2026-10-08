package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.ScoreboardManager;
import com.fortuneandfavors.economy.ScoreboardManager.Settings;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The screen {@code /stats scoreboard edit} opens: every line of the board, as a switch.
 *
 * <h2>Why a menu rather than more command arguments</h2>
 * The board has ten body lines and each one is a yes/no. As command arguments that is twenty
 * spellings to remember, and the moment a player cannot see the board while they are editing
 * it they will not use it. As a row of switches it is one click per line and the lore under
 * each one says what the line currently reads - so this is the board, in the order the board
 * draws it, with the state written on the face of every button.
 *
 * <h2>Order, and which text is the banner</h2>
 * Two things here are not switches. A board is read top to bottom, so the order matters and it is
 * the player's to choose: right-click a line to pick it up, then the two arrows move it. And the
 * board has exactly two written texts - the banner and the address - so one button swaps which of
 * them is promoted to the top; the other drops into the body at the {@code ip} slot, which is why
 * that slot's name changes when it is swapped. A third text, the player's own line, is a body line
 * like the rest and is written from its own tile.
 *
 * <h2>Typing, inside the screen</h2>
 * The two *written* texts are retyped from here as well: the title tile and the address tile each
 * open an anvil prompt ({@link ScoreboardTextMenu}), which is vanilla's own text box and the same
 * one this mod already asks for token names and shop prices with. The commands
 * ({@code /ff scoreboard title <text>}, {@code /ff scoreboard ip <text>}) stay, because a player
 * who knows what they want to write should not have to open a window to write it.
 *
 * <h2>What this screen is showing you</h2>
 * A sidebar cannot be drawn inside a chest, so the board is shown the way a chest can show it:
 * the tiles are the board top to bottom, in the order it draws them, each one carrying its live
 * text. All of it is <b>this player's own board</b> - the objective and the display packet behind
 * it are per player, so nothing here changes what anybody else sees.
 *
 * <h2>Terracotta and glass</h2>
 * A switch that is on is a lime-stained pane and one that is off is a red one, with the line's
 * own icon showing what it is a switch for. Colour alone is not the message on purpose - the
 * name and the lore both say "ON" or "OFF" - because a screen that only distinguishes itself
 * by hue is a screen half the people reading it cannot use.
 */
public class ScoreboardEditMenu extends ChestMenu {
   private static final int SLOTS = 27;
   /**
    * The row the controls sit on.
    *
    * <p>It used to be slots 6-11, because the board carried six lines and the tiles were the first
    * six slots. The board carries ten now, so the tiles own slots 0-9 and everything that is not a
    * line starts here - a chest menu has no layout engine, so "the tiles are the board, the
    * controls are underneath" is a number that has to be kept bigger than the line count.
    */
   private static final int CONTROLS = ScoreboardManager.LINE_KEYS.length;
   private static final int BANNER = CONTROLS;
   private static final int UP = CONTROLS + 1;
   private static final int DOWN = CONTROLS + 2;
   private static final int TITLE = CONTROLS + 3;
   private static final int ADDRESS = CONTROLS + 4;
   private static final int CUSTOM_TEXT = CONTROLS + 5;
   private static final int MASTER = CONTROLS + 6;
   private static final int PREVIEW = 22;
   private static final int CLOSE = 26;

   private final SimpleContainer container;
   private final ServerPlayer owner;
   /** The line currently picked up for reordering, or null. */
   private String selected;

   private ScoreboardEditMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   /**
    * Opens the board's screen, turning the board on first.
    *
    * <p>Switched on here rather than by each caller, because the screen is the board: every tile
    * in it reports what the board is doing this second, and a player who opened it and saw
    * nothing change would click a switch twice to find out whether it worked. It also means the
    * hub, the command and the prompt all land in the same state.
    */
   public static void open(ServerPlayer player) {
      ScoreboardManager.enable(player);
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new ScoreboardEditMenu(syncId, inv, new SimpleContainer(SLOTS)), Component.literal("§6§lYour Scoreboard"))
      );
   }

   private void rebuild() {
      Settings s = ScoreboardManager.settingsOf(this.owner);

      // The line tiles, in the order the board draws them, each carrying its own icon and its
      // live text. Slot position *is* the order, so the screen reads like the board.
      List<String> order = ScoreboardManager.orderOf(s);
      // The board as it reads this second, read once: every tile's lore quotes a line of it, and
      // building the board per tile was ten reads of the same second's board.
      List<String> drawn = ScoreboardManager.lines(this.owner);
      ItemStack filler = this.filler();
      for (int i = 0; i < SLOTS; i++) {
         this.container.setItem(i, filler.copy());
      }

      // One icon per line key, in LINE_KEYS order: the icon is what the line is about, so a new
      // line is a new icon here and nothing else has to know it exists.
      Item[] icons = {
         Items.COMPASS,
         Items.NAME_TAG,
         Items.SHIELD,
         Items.AMETHYST_SHARD,
         Items.GOLD_INGOT,
         Items.PLAYER_HEAD,
         Items.EXPERIENCE_BOTTLE,
         Items.CLOCK,
         Items.PAPER,
         Items.WRITABLE_BOOK
      };
      if (icons.length < ScoreboardManager.LINE_KEYS.length) {
         Item[] grown = new Item[ScoreboardManager.LINE_KEYS.length];
         java.util.Arrays.fill(grown, Items.PAPER);
         System.arraycopy(icons, 0, grown, 0, icons.length);
         icons = grown;
      }
      for (int i = 0; i < order.size(); i++) {
         String key = order.get(i);
         boolean on = ScoreboardManager.lineOn(s, key);
         boolean picked = key.equals(this.selected);
         StringBuilder name = new StringBuilder((on ? "§a§l" : "§c§l") + ScoreboardManager.lineName(key) + (on ? " §aON" : " §cOFF"));
         if (picked) {
            name.insert(0, "§e▶ ");
         }
         ItemStack stack = new ItemStack(icons[keyIndex(key)]);
         stack.set(DataComponents.CUSTOM_NAME, Component.literal(name.toString()));
         stack.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Position §f" + (i + 1) + "§7 of §f" + order.size()),
                  Component.literal("§8Now: " + this.currentLine(drawn, i)),
                  Component.literal(""),
                  Component.literal("§7Left-click to turn this line " + (on ? "§coff" : "§aon") + "§7."),
                  Component.literal(picked ? "§ePicked up - use §f▲§e/§f▼§e to move it." : "§7Right-click to pick up for reordering.")
               )
            )
         );
         this.container.setItem(i, stack);
      }

      // The banner swap: which of the two texts sits on top.
      ItemStack banner = new ItemStack(Items.ITEM_FRAME);
      banner.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lBanner: §r" + ScoreboardManager.banner(s, this.owner)));
      banner.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7On top right now: §r" + (s.bannerIsIp ? "the address" : "the title")),
               Component.literal(""),
               Component.literal("§7Click to promote " + (s.bannerIsIp ? "§fthe title" : "§fthe address") + "§7 instead."),
               Component.literal("§8The other text drops into the §7Address§8 line.")
            )
         )
      );
      this.container.setItem(BANNER, banner);

      // The two reorder arrows. They act on the picked-up line, or say what to do if none is.
      this.container.setItem(UP, this.arrow("§e§l▲ Move up", this.selected == null ? "§8Right-click a line to pick it up first." : "§7Moves §f" + ScoreboardManager.lineName(this.selected) + "§7 up one place."));
      this.container.setItem(DOWN, this.arrow("§e§l▼ Move down", this.selected == null ? "§8Right-click a line to pick it up first." : "§7Moves §f" + ScoreboardManager.lineName(this.selected) + "§7 down one place."));

      // The two typed texts, as tiles that open the prompt that writes them.
      this.container.setItem(
         TITLE,
         this.textTile(Items.NAME_TAG, "§6§lTitle", s.title, "§7Click to retype the banner - the", "§7name across the top of your board.")
      );
      // The tile shows the address the board actually prints, which is normally the one this player
      // joined on and is not stored anywhere: the box behind it is an override, and the lore says so
      // rather than leaving a player to wonder where the text came from.
      String live = ScoreboardManager.serverAddress(this.owner);
      this.container.setItem(
         ADDRESS,
         this.textTile(
            Items.COMPASS,
            "§6§lAddress",
            live == null ? "" : live,
            "§7Click to override the address line.",
            "§7Empty means the one you joined on.",
            s.serverIp == null || s.serverIp.isBlank() ? "§8(filling itself in)" : "§8(set by you)"
         )
      );
      this.container.setItem(
         CUSTOM_TEXT,
         this.textTile(
            Items.WRITABLE_BOOK,
            "§6§lYour Own Line",
            s.customText,
            "§7Click to write the line that is entirely",
            "§7yours - a motto, a goal, a hello.",
            "§8Blank is allowed: the line just draws nothing."
         )
      );

      // The master switch: the board itself, on or off for this player. It is the same switch as
      // /ff scoreboard enable|disable, in the place a player looking at their board will find it.
      ItemStack master = new ItemStack(s.enabled ? Items.DYE.lime() : Items.DYE.gray());
      master.set(
         DataComponents.CUSTOM_NAME,
         Component.literal(s.enabled ? "§a§lBoard: ON" : "§c§lBoard: OFF")
      );
      master.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal(s.enabled ? "§7Shown in your sidebar." : "§7Not shown right now."),
               Component.literal("§7Click to turn your board " + (s.enabled ? "§coff" : "§aon") + "§7."),
               Component.literal("§8Only you ever see this board.")
            )
         )
      );
      this.container.setItem(MASTER, master);

      // The preview is the board as it reads right now - the banner first, then every line that
      // is switched on, in the order they are drawn. A chest cannot draw a sidebar, so this is
      // the board, written down: the same lines the client is being sent this second.
      ItemStack preview = new ItemStack(Items.PAPER);
      preview.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lYour board right now"));
      List<Component> shown = new ArrayList<>();
      shown.add(Component.literal("§8" + (s.enabled ? "shown" : "hidden") + " · only you see this"));
      shown.add(Component.literal("§8─".repeat(18)));
      shown.add(Component.literal(ScoreboardManager.banner(s, this.owner)));
      for (String line : drawn) {
         shown.add(Component.literal(" " + line));
      }
      if (drawn.isEmpty()) {
         shown.add(Component.literal("§8  (every line is switched off)"));
      }
      shown.add(Component.literal("§8─".repeat(18)));
      shown.add(Component.literal("§7Left-click a line to switch it, right-click"));
      shown.add(Component.literal("§7to pick it up for reordering."));
      preview.set(DataComponents.LORE, new ItemLore(shown));
      this.container.setItem(PREVIEW, preview);

      this.container.setItem(CLOSE, this.closeStack());
      this.broadcastChanges();
   }

   private static int keyIndex(String key) {
      for (int i = 0; i < ScoreboardManager.LINE_KEYS.length; i++) {
         if (ScoreboardManager.LINE_KEYS[i].equals(key)) {
            return i;
         }
      }
      return 0;
   }

   private ItemStack arrow(String name, String hint) {
      ItemStack stack = new ItemStack(this.selected == null ? Items.ARROW : Items.SPECTRAL_ARROW);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(hint))));
      return stack;
   }

   /** One row of the board as it reads right now, for the switch's own lore. */
   private String currentLine(List<String> drawn, int lineIndex) {
      return lineIndex >= drawn.size() ? "§8(hidden)" : drawn.get(lineIndex);
   }

   /** A tile for one of the two written texts: what it says now, and that clicking retypes it. */
   private ItemStack textTile(Item item, String name, String current, String... loreLines) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§8Now: §r" + ScoreboardManager.colorize(current)));
      lore.add(Component.literal(""));
      for (String line : loreLines) {
         lore.add(Component.literal(line));
      }
      lore.add(Component.literal("§8" + ScoreboardManager.MAX_TEXT + " chars · colour codes work"));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private ItemStack closeStack() {
      ItemStack stack = new ItemStack(Items.BARRIER);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lClose"));
      return stack;
   }

   private ItemStack filler() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8§l "));
      return stack;
   }

   @Override
   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }

      if (slotId >= 0 && slotId < SLOTS) {
         Settings s = ScoreboardManager.settingsOf(sp);
         List<String> order = ScoreboardManager.orderOf(s);
         if (slotId < order.size()) {
            String key = order.get(slotId);
            if (button == 1) {
               // Right-click picks a line up for reordering; left-click keeps the obvious job of
               // toggling, so a player who never reorders anything never meets this at all.
               this.selected = key.equals(this.selected) ? null : key;
            } else if (!ScoreboardManager.lineAvailable(sp, key)) {
               // Single player has no address to print, so the tile says so instead of storing a
               // switch the board then refuses to honour.
               Chat.msg(sp, "&7There is no address to print in single player. &f/ff scoreboard ip <text>&7 works on a server.");
            } else {
               ScoreboardManager.toggleLine(s, key);
               ScoreboardManager.show(sp);
            }
         } else if (slotId == BANNER) {
            ScoreboardManager.setBanner(s, !s.bannerIsIp);
            ScoreboardManager.show(sp);
         } else if (slotId == TITLE) {
            this.returnCarried(sp);
            ScoreboardTextMenu.open(sp, ScoreboardTextMenu.TITLE);
            return;
         } else if (slotId == ADDRESS) {
            this.returnCarried(sp);
            ScoreboardTextMenu.open(sp, ScoreboardTextMenu.ADDRESS);
            return;
         } else if (slotId == CUSTOM_TEXT) {
            this.returnCarried(sp);
            ScoreboardTextMenu.open(sp, ScoreboardTextMenu.CUSTOM);
            return;
         } else if (slotId == MASTER) {
            if (s.enabled) {
               ScoreboardManager.disable(sp);
            } else {
               ScoreboardManager.enable(sp);
            }
         } else if (slotId == UP || slotId == DOWN) {
            if (this.selected != null) {
               ScoreboardManager.moveLine(s, this.selected, slotId == UP ? -1 : 1);
               ScoreboardManager.show(sp);
            }
         } else if (slotId == CLOSE) {
            sp.closeContainer();
            this.returnCarried(sp);
            return;
         }

         this.returnCarried(sp);
         this.rebuild();
      } else if (input != ContainerInput.QUICK_MOVE && input != ContainerInput.CLONE) {
         super.clicked(slotId, button, input, player);
      } else {
         this.returnCarried(sp);
      }
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }

   @Override
   public ItemStack quickMoveStack(Player player, int index) {
      if (index >= 0 && index < SLOTS) {
         this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
      }
      return ItemStack.EMPTY;
   }
}
