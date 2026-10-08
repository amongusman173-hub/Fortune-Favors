package com.fortuneandfavors.menu;

import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
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

/** The bare /ff command opens this OP command hub: every admin subcommand in
 *  one window. Each button runs the matching /ff command through the server
 *  dispatcher, so behaviour is identical to typing it. */
public class OpCommandMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int CLOSE = 44;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public OpCommandMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(45));
   }

   private OpCommandMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x5, syncId, playerInventory, container, 5);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new OpCommandMenu(syncId, inv), Component.literal("§c§lFF Admin Hub")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 5, Items.STAINED_GLASS_PANE.red());
      ItemStack info = new ItemStack(Items.COMMAND_BLOCK);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lFF Admin Hub"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Every OP command behind /ff,"),
               Component.literal("§7one click away. Each button runs"),
               Component.literal("§7the same command as typing it.")
            )
         )
      );
      this.container.setItem(INFO, info);

      int slot = 9;
      slot = this.add(slot, Items.CHEST, "§6Give Items", "ff give", "§7Open the item catalogue to hand anything out.");
      slot = this.add(slot, Items.COMPARATOR, "§eConfig", "config screen", "§7Toggle features and settings for the whole server.");
      slot = this.add(slot, Items.FIREWORK_ROCKET, "§dEvents", "ff event", "§7Start, list, or stop server events.");
      slot = this.add(slot, Items.REDSTONE, "§cRestore", "ff restore", "§7Preview & restore an inventory from backup.");
      slot = this.add(slot, Items.EMERALD, "§aGems", "ff gems <player> <amount>", "§7Give a player gems. Needs the player + amount.");
      slot = this.add(slot, Items.GLASS, "§bVanish", "ff vanish", "§7Become invisible to everyone but admins.");
      slot = this.add(slot, Items.BARREL, "§6Backup", "ff backup", "§7Force a full data backup right now.");
      slot = this.add(slot, Items.NAME_TAG, "§dTitle", "ff title", "§7Show your current title, or set one.");
      slot = this.add(slot, Items.CLOCK, "§eStreak", "ff streak", "§7Inspect your login streak stats.");
      slot = this.add(slot, Items.EXPERIENCE_BOTTLE, "§bChallenges", "ff challenges", "§7View your daily & weekly challenges.");
      slot = this.add(slot, Items.BOOK, "§dCodex", "ff codex", "§7Every boss ever slain, and who did it.");
      slot = this.add(slot, Items.DRAGON_HEAD, "§5Records", "ff records", "§7The permanent hall of firsts.");
      slot = this.add(slot, Items.ENDER_EYE, "§cTest: Corruption", "ff test corruption 1", "§7Spawn corruption stage 1 (testing).");
      slot = this.add(slot, Items.PHANTOM_MEMBRANE, "§cTest: Illusion", "ff test illusion", "§7Spawn the illusion boss (testing).");
      slot = this.add(slot, Items.SNOWBALL, "§cTest: Snow Queen", "ff test snowqueen", "§7Spawn the Snow Queen (testing).");
      slot = this.add(slot, Items.SKELETON_SKULL, "§cTest: Betrayal", "ff test betrayal", "§7Trigger a betrayal raid (testing).");
      slot = this.add(slot, Items.COMPASS, "§bCompass FX", "ff compassfx", "§7Toggle death-compass particle effects.");
      slot = this.add(slot, Items.SHIELD, "§dClaim Soulbound", "ff claim-soulbound", "§7Claim a lost soulbound item.");
      slot = this.add(slot, Items.END_PORTAL_FRAME, "§5Leave Realm", "ff leaverealm", "§7Escape the duel/expedition realm.");
      slot = this.add(slot, Items.WRITABLE_BOOK, "§7Help", "ff help", "§7Overview of every command the mod adds.");
      slot = this.add(slot, Items.KNOWLEDGE_BOOK, "§7Version", "ff version", "§7Which version of Fortune & Favors is running.");

      this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose"));
   }

   private int add(int slot, Item item, String name, String run, String lore) {
      if (slot % 9 == 8) {
         slot += 2; // skip the border column
      }
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal(lore),
               Component.literal("§8Runs: §f" + run),
               Component.literal("§8Click to run")
            )
         )
      );
      this.container.setItem(slot, stack);
      return slot + 1;
   }

   private ItemStack named(Item item, String name) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
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

   private void run(ServerPlayer sp, String command) {
      sp.level().getServer().getCommands().performPrefixedCommand(sp.createCommandSourceStack(), command);
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         switch (slotId) {
            case 9:
               this.run(sp, "ff give");
               break;
            case 10:
               // Open the screen directly rather than dispatching `ff config`: the
               // command goes through the dispatcher and re-checks permissions,
               // and a chat error is a poor answer to a button that is right there.
               sp.closeContainer();
               FeatureConfigMenu.open(sp);
               this.returnCarried(sp);
               return;
            case 11:
               this.run(sp, "ff event");
               break;
            case 12:
               this.run(sp, "ff restore");
               break;
            case 13:
               this.run(sp, "ff gems " + sp.getName().getString() + " 1");
               break;
            case 14:
               this.run(sp, "ff vanish");
               break;
            case 15:
               this.run(sp, "ff backup");
               break;
            case 16:
               this.run(sp, "ff title");
               break;
            case 19:
               this.run(sp, "ff streak");
               break;
            case 20:
               this.run(sp, "ff challenges");
               break;
            case 21:
               this.run(sp, "ff codex");
               break;
            case 22:
               this.run(sp, "ff records");
               break;
            case 23:
               this.run(sp, "ff test corruption 1");
               break;
            case 24:
               this.run(sp, "ff test illusion");
               break;
            case 25:
               this.run(sp, "ff test snowqueen");
               break;
            case 28:
               this.run(sp, "ff test betrayal");
               break;
            case 29:
               this.run(sp, "ff compassfx");
               break;
            case 30:
               this.run(sp, "ff claim-soulbound");
               break;
            case 31:
               this.run(sp, "ff leaverealm");
               break;
            case 32:
               this.run(sp, "ff help");
               break;
            case 33:
               this.run(sp, "ff version");
               break;
            case CLOSE:
               this.returnCarried(sp);
               sp.closeContainer();
               return;
            default:
               this.returnCarried(sp);
               break;
         }

         this.returnCarried(sp);
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}