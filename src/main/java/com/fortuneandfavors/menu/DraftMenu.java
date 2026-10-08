package com.fortuneandfavors.menu;

import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;

public class DraftMenu extends ChestMenu {
   private static final int SLOTS = 27;
   private static final int KIT_SLOTS = 24;
   private static final int PICKS_SLOT = 24;
   private static final int TIMER_SLOT = 25;
   private static final int CLOSE_SLOT = 26;
   private static final Map<UUID, DraftMenu> OPEN = new HashMap<>();
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private int lastShown = -1;

   private DraftMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      OPEN.put(this.owner.getUUID(), this);
      this.rebuild();
   }

   public void removed(Player player) {
      OPEN.remove(this.owner.getUUID());
      super.removed(player);
   }

   public static void tickAll() {
      for (DraftMenu m : new ArrayList<>(OPEN.values())) {
         if (m.owner != null && m.owner.isAlive() && m.owner.containerMenu == m) {
            m.updateTimer();
         } else {
            OPEN.remove(m.owner.getUUID());
         }
      }
   }

   public static void rebuildAll() {
      for (DraftMenu m : new ArrayList<>(OPEN.values())) {
         if (m.owner != null && m.owner.isAlive() && m.owner.containerMenu == m) {
            m.rebuild();
         } else {
            OPEN.remove(m.owner.getUUID());
         }
      }
   }

   private void updateTimer() {
      int left = DuelManager.draftSecondsLeft(this.owner);
      if (left != this.lastShown) {
         this.lastShown = left;
         ItemStack timer = new ItemStack(
            left > 5
               ? Items.STAINED_GLASS_PANE.pick(DyeColor.GREEN)
               : (left > 2 ? Items.STAINED_GLASS_PANE.pick(DyeColor.YELLOW) : Items.STAINED_GLASS_PANE.pick(DyeColor.RED)),
            Math.max(1, Math.min(64, left))
         );
         timer.set(DataComponents.CUSTOM_NAME, Component.literal((left > 5 ? "§a" : (left > 2 ? "§e" : "§c")) + "§lTime left: " + Math.max(0, left) + "s"));
         timer.set(
            DataComponents.LORE,
            new ItemLore(List.of(Component.literal("§7Pick now or a random kit" + (left <= 2 ? " §cwill be assigned!" : " gets assigned"))))
         );
         this.container.setItem(25, timer);
         this.broadcastChanges();
      }
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new DraftMenu(syncId, inv, new SimpleContainer(27)), Component.literal("§b§lDraft Your Kit")));
   }

   private void rebuild() {
      List<String> pool = DuelManager.draftPoolFor(this.owner);
      int i = 0;

      for (String kit : pool) {
         if (i >= 24) {
            break;
         }

         this.container.setItem(i++, this.draftStack(kit));
      }

      while (i < 24) {
         this.container.setItem(i, this.filler());
         i++;
      }

      int picks = DuelManager.draftPicksFor(this.owner);
      boolean myTurn = DuelManager.draftIsMyTurn(this.owner);
      ItemStack count = new ItemStack(myTurn ? Items.DYE.lime() : Items.DYE.gray());
      count.set(DataComponents.CUSTOM_NAME, Component.literal(myTurn ? "§a§lYOUR TURN - Pick a kit!" : "§7§lWaiting for opponent..."));
      count.set(
         DataComponents.LORE,
         new ItemLore(List.of(Component.literal("§7Picks: " + picks + "/5"), Component.literal(myTurn ? "§aClick a kit above" : "§7Opponent is picking...")))
      );
      this.container.setItem(24, count);
      this.lastShown = -1;
      this.updateTimer();
      ItemStack close = new ItemStack(Items.BARRIER);
      close.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lClose"));
      this.container.setItem(26, close);
      this.broadcastChanges();
   }

   private ItemStack draftStack(String kit) {
      ItemStack s = switch (kit) {
         case "sword" -> new ItemStack(Items.DIAMOND_SWORD);
         case "bow" -> new ItemStack(Items.BOW);
         case "armor_diamond" -> new ItemStack(Items.DIAMOND_CHESTPLATE);
         case "armor_iron" -> new ItemStack(Items.IRON_CHESTPLATE);
         case "blocks" -> new ItemStack(Items.WOOL.pick(DyeColor.WHITE), 16);
         case "pearl" -> new ItemStack(Items.ENDER_PEARL, 2);
         case "heal" -> new ItemStack(Items.SPLASH_POTION);
         case "speed" -> new ItemStack(Items.SPLASH_POTION);
         case "gapple" -> new ItemStack(Items.GOLDEN_APPLE, 4);
         case "gapple_head" -> DuelManager.goldenAppleHead(4);
         case "axe" -> new ItemStack(Items.IRON_AXE);
         case "shield" -> new ItemStack(Items.SHIELD);
         default -> new ItemStack(Items.FISHING_ROD);
      };
      s.set(DataComponents.CUSTOM_NAME, Component.literal("§f§l" + DuelManager.draftCategoryDisplayName(kit)));
      s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Click to draft - it vanishes for both fighters"))));
      return s;
   }

   private ItemStack filler() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8§l "));
      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId >= 0 && slotId < 27) {
            List<String> pool = DuelManager.draftPoolFor(sp);
            if (slotId < 24 && slotId < pool.size()) {
               String err = DuelManager.pickDraft(sp, pool.get(slotId));
               if (err != null) {
                  Chat.msg(sp, "&c" + err);
               }

               sp.closeContainer();
            } else {
               if (slotId == 26) {
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
      if (index >= 0 && index < 27) {
         this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
      }

      return ItemStack.EMPTY;
   }
}
