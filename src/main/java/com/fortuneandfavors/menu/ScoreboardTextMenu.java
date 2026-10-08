package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.ScoreboardManager;
import com.fortuneandfavors.economy.ScoreboardManager.Settings;
import com.fortuneandfavors.util.Chat;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The rename prompt behind the scoreboard screen: the banner text, the address line, or the line
 * the player writes for themselves.
 *
 * <h2>Why an anvil</h2>
 * A chest menu can flip a switch in one click, but it cannot take a string - and the texts on this
 * board are the two things a player most wants to change. The anvil is vanilla's own text box and
 * this mod already uses it everywhere else a player types something (token names, shop prices,
 * claim permissions), so the board's texts are asked for the same way and the player only has to
 * learn one prompt.
 *
 * <h2>Which text, without a field</h2>
 * The target is carried by the <b>input item</b> rather than by a field of this class: the name
 * tag asks for the banner, the paper for the address and the book for the player's own line. That
 * is not a trick for its own sake - {@code AnvilPromptMenu}'s constructor renders the first
 * preview, and a subclass field is not assigned until the constructor body runs, so a flag kept in
 * a field would draw one frame with the wrong label. An item the superclass was handed cannot be
 * in that state.
 *
 * <h2>What the text may be</h2>
 * One to {@link ScoreboardManager#MAX_TEXT} characters for the banner and the address, colour codes
 * welcome, and the input item is never handed back to the player - it is a prompt, not an item,
 * which is why {@code returnInputOnCancel} is off. The player's own line is the one that may be
 * emptied: it is decoration, and a blank line simply draws nothing.
 */
public class ScoreboardTextMenu extends AnvilPromptMenu {
   /** The three texts this prompt can be asked for. */
   public static final int TITLE = 0;
   public static final int ADDRESS = 1;
   public static final int CUSTOM = 2;

   private ScoreboardTextMenu(int syncId, Inventory playerInventory, int which, String current) {
      super(syncId, playerInventory, prompt(which, current), helper(heading(which), loreOf(which)));
      // A prompt, not an item: the item above is not the player's and must not fall into their
      // inventory on close.
      this.returnInputOnCancel = false;
   }

   public static void open(ServerPlayer player, int which) {
      Settings current = ScoreboardManager.settingsOf(player);
      String text = switch (which) {
         // Prefilled with what the board prints right now, which for an untouched address is the one
         // this player joined on - so the box opens on the real address rather than on an empty box
         // the player cannot tell the meaning of. Clearing it goes back to the live one.
         case ADDRESS -> ScoreboardManager.serverAddress(player) == null ? "" : ScoreboardManager.serverAddress(player);
         case CUSTOM -> current.customText;
         default -> current.title;
      };
      int target = which;
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new ScoreboardTextMenu(syncId, inv, target, text),
            Component.literal(switch (target) {
               case ADDRESS -> "§6§lAddress line";
               case CUSTOM -> "§6§lYour own line";
               default -> "§6§lBoard title";
            })
         )
      );
   }

   private static String heading(int which) {
      return switch (which) {
         case ADDRESS -> "§e§lThe address line";
         case CUSTOM -> "§e§lYour own line";
         default -> "§e§lThe banner";
      };
   }

   private static String subtitle(int which) {
      return switch (which) {
         case ADDRESS -> "§7The address your board shows. It fills itself in with the one you joined on.";
         case CUSTOM -> "§7A line that is entirely yours, on your board.";
         default -> "§7The name across the top of your board.";
      };
   }

   /** The helper item's own lines: what the box is for, what may go in it, and who sees it. */
   private static String[] loreOf(int which) {
      String limit = "§8" + ScoreboardManager.MAX_TEXT + " characters at most.";
      return switch (which) {
         case ADDRESS -> new String[] {
            "§7The real address fills itself in - the one",
            "§7this player joined through - so this box is",
            "§7only for overriding it. Empty means live.",
            limit,
            "§8Only you ever see this board."
         };
         case CUSTOM -> new String[] {
            subtitle(CUSTOM), "§7Type it above, then click the result to", "§7put it on your board. Colour codes work:", "§c&c§6&6§a&a§b&b§d&d", limit,
            "§8Blank is allowed - the line draws nothing."
         };
         default -> new String[] {
            subtitle(TITLE), "§7Type it above, then click the result to", "§7save it. Colour codes work: §c&c§6&6§a&a§b&b§d&d", limit, "§8Only you ever see this board."
         };
      };
   }

   /**
    * The input item: its type says which text is being written, its name is what it says now.
    *
    * <p>Three items for three targets - name tag, paper, book - because the item is the only thing
    * that survives the constructor's first render (see the class note).
    */
   private static ItemStack prompt(int which, String current) {
      ItemStack stack = new ItemStack(switch (which) {
         case ADDRESS -> Items.PAPER;
         case CUSTOM -> Items.WRITABLE_BOOK;
         default -> Items.NAME_TAG;
      });
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(current == null ? "" : current));
      return stack;
   }

   /** Which text this prompt is writing, read back off the item it was opened with. */
   private int target() {
      ItemStack input = this.inputSlots.getItem(0);
      if (input.is(Items.PAPER)) {
         return ADDRESS;
      }
      if (input.is(Items.WRITABLE_BOOK)) {
         return CUSTOM;
      }
      return TITLE;
   }

   @Override
   protected boolean canAccept(String text) {
      String s = text == null ? "" : text.trim();
      // Two texts may be blank. The player's own line, because it is decoration rather than the
      // board's identity - and the address, where blank is not "nothing" but "the address you joined
      // on": the box is an override, and emptying it is how a player gives the real one back.
      if (this.target() == CUSTOM || this.target() == ADDRESS) {
         return s.length() <= ScoreboardManager.MAX_TEXT;
      }
      return !s.isEmpty() && s.length() <= ScoreboardManager.MAX_TEXT;
   }

   @Override
   protected void renderResult(ItemStack result, String text) {
      String s = text == null ? "" : text.trim();
      if (this.canAccept(s)) {
         // The result carries the player's own text, colourised, so the preview is what the
         // board will actually read rather than an echo of what was typed.
         result.set(
            DataComponents.CUSTOM_NAME,
            Component.literal(
               this.target() == CUSTOM && s.isEmpty()
                  ? "§8(blank line)"
                  : this.target() == ADDRESS && s.isEmpty() ? "§8(the address you joined on)" : ScoreboardManager.colorize(s)
            )
         );
         result.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Click to put this on your board."),
                  Component.literal("§8Only you ever see it."),
                  Component.literal("§8Right-click the prompt to clear the box.")
               )
            )
         );
      } else {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lType the text above"));
         result.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal(
                     switch (this.target()) {
                        case ADDRESS -> "§7e.g. §fplay.example.net";
                        case CUSTOM -> "§7e.g. §fMining to 60";
                        default -> "§7e.g. §f&6&lMy Server";
                     }
                  ),
                  Component.literal("§8Empty is not allowed - use a line"),
                  Component.literal("§8switch to hide a line instead.")
               )
            )
         );
      }
   }

   @Override
   protected void accept(ServerPlayer player, String text) {
      int which = this.target();
      String stored = switch (which) {
         case ADDRESS -> ScoreboardManager.setText(player, true, text);
         case CUSTOM -> ScoreboardManager.setCustomText(player, text);
         default -> ScoreboardManager.setText(player, false, text);
      };
      ScoreboardManager.show(player);
      String label = switch (which) {
         case ADDRESS -> "Address";
         case CUSTOM -> "Your line";
         default -> "Title";
      };
      Chat.msg(
         player,
         "&a" + label + " set to &r" + (stored.isEmpty() ? "&8(nothing)" : ScoreboardManager.colorize(stored)) + "&a. &7Only you see this board."
      );
   }

   @Override
   protected void reopen(ServerPlayer player) {
      // Straight back to the board, not to the game: a player renaming the title is almost
      // always about to change something else on it too.
      ScoreboardEditMenu.open(player);
   }
}
