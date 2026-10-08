package com.fortuneandfavors.menu;

import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.duel.DuelManager.BotDifficulty;
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
import net.minecraft.world.level.ItemLike;

public class BotDifficultyMenu extends ChestMenu {
   private static final int ROWS = 1;
   private static final int SLOTS = 9;
   private static final int CLOSE = 8;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   private BotDifficultyMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x1, syncId, playerInventory, container, 1);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new BotDifficultyMenu(syncId, inv, new SimpleContainer(9)), Component.literal("§6§lBot Difficulty"))
      );
   }

   private void rebuild() {
      this.difficulty(0, Items.EMERALD, "§a§lEasy", "§7Slower movement, low CPS, no strafing");
      this.difficulty(1, Items.GOLD_INGOT, "§e§lNormal", "§7Normal speed, 6-8 CPS, no strafing");
      this.difficulty(2, Items.REDSTONE, "§c§lHard", "§7Fast, 10-12 CPS, strafing and dodging");
      boolean hackerUnlocked = DuelManager.isHackerUnlocked(this.owner);
      if (hackerUnlocked) {
         this.difficulty(3, Items.NETHERITE_SWORD, "§5§l⚔ Hacker ⚔", "§7§o15 CPS, extreme speed, always strafes, 100% dodge");
      } else {
         ItemStack locked = new ItemStack(Items.BARRIER);
         locked.set(DataComponents.CUSTOM_NAME, Component.literal("§8§l??? Locked ???"));
         locked.set(
            DataComponents.LORE,
            new ItemLore(List.of(Component.literal("§7Win 3 in a row against Hard bot"), Component.literal("§7on 3 different game modes to unlock")))
         );
         this.container.setItem(3, locked);
      }

      // The builder sits beside the presets rather than hidden behind a command.
      // It is a difficulty, and the screen that picks a difficulty is where a player
      // looks for one.
      ItemStack custom = new ItemStack(Items.CRAFTING_TABLE);
      custom.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lCustom Bot"));
      custom.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Build the bot: speed, strafing, CPS, smartness"),
               Component.literal("§8Current: " + DuelManager.customProfileOf(this.owner).describe())
            )
         )
      );
      this.container.setItem(4, custom);

      // The practice dummy sits with the presets rather than behind a command: it is a
      // difficulty, and the screen that picks one is where a player looks for it.
      this.difficulty(5, Items.ARMOR_STAND, "§6§lTraining Dummy", "§7No AI, infinite health, at the arena centre - practise freely");

      for (int i = 6; i < 8; i++) {
         this.container.setItem(i, this.filler());
      }

      this.container.setItem(8, this.closeStack());
      this.broadcastChanges();
   }

   private void difficulty(int slot, Item item, String name, String lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      this.container.setItem(slot, stack);
   }

   private ItemStack closeStack() {
      ItemStack stack = new ItemStack(Items.BARRIER);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lCancel"));
      return stack;
   }

   private ItemStack filler() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8§l "));
      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId >= 0 && slotId < 9) {
            BotDifficulty chosen = null;
            if (slotId == 0) {
               chosen = BotDifficulty.EASY;
            } else if (slotId == 1) {
               chosen = BotDifficulty.NORMAL;
            } else if (slotId == 2) {
               chosen = BotDifficulty.HARD;
            } else if (slotId == 3 && DuelManager.isHackerUnlocked(sp)) {
               chosen = BotDifficulty.HACKER;
            } else if (slotId == 5) {
               chosen = BotDifficulty.TRAIN;
            }

            if (chosen != null) {
               DuelManager.beginChallenge(sp, "bot", chosen);
               sp.closeContainer();
               DuelModeMenu.open(sp);
            } else {
               if (slotId == 4) {
                  CustomBotMenu.open(sp);
                  return;
               }

               if (slotId == 8) {
                  sp.closeContainer();
               }

               this.returnCarried(sp);
            }
         } else if (input != ContainerInput.QUICK_MOVE && input != ContainerInput.CLONE) {
            super.clicked(slotId, button, input, player);
         } else {
            this.returnCarried(sp);
         }
      } else {
         super.clicked(slotId, button, input, player);
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

   public ItemStack quickMoveStack(Player player, int index) {
      if (index >= 0 && index < 9) {
         this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
      }

      return ItemStack.EMPTY;
   }
}
