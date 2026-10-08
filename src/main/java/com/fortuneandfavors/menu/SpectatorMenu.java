package com.fortuneandfavors.menu;

import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.util.Chat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;

public class SpectatorMenu extends AbstractContainerMenu {
   private static final int SLOTS = 9;
   private static final Set<SpectatorMenu> OPEN = ConcurrentHashMap.newKeySet();
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final UUID fighter0;
   private final UUID fighter1;
   private final String name0;
   private final String name1;

   private SpectatorMenu(int syncId, Inventory playerInventory, SimpleContainer container, UUID fighter0, UUID fighter1, String name0, String name1) {
      super(MenuType.GENERIC_9x1, syncId);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.fighter0 = fighter0;
      this.fighter1 = fighter1;
      this.name0 = name0;
      this.name1 = name1;

      for (int i = 0; i < 9; i++) {
         this.addSlot(new Slot(container, i, 0, 0));
      }

      OPEN.add(this);
      this.rebuild();
   }

   public static void open(ServerPlayer spectator, UUID fighter0, UUID fighter1, String name0, String name1) {
      spectator.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new SpectatorMenu(syncId, inv, new SimpleContainer(9), fighter0, fighter1, name0, name1),
            Component.literal("§d§lDuel Spectator")
         )
      );
   }

   private void rebuild() {
      this.container.setItem(0, betStack(this.name0, "§c"));
      this.container.setItem(1, betStack(this.name1, "§9"));
      ItemStack toggle = new ItemStack(Items.ENDER_EYE);
      toggle.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lToggle Mode"));
      boolean isSpec = this.owner.gameMode.getGameModeForPlayer().toString().equals("SPECTATOR");
      toggle.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Current: " + (isSpec ? "§bSpectator" : "§aSurvival")),
               Component.literal(""),
               Component.literal("§7Click to switch to " + (isSpec ? "§aSurvival" : "§bSpectator"))
            )
         )
      );
      this.container.setItem(4, toggle);
      ItemStack leave = new ItemStack(Items.BARRIER);
      leave.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lLeave"));
      leave.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Exit the spectator area"))));
      this.container.setItem(8, leave);

      for (int i = 0; i < 9; i++) {
         if (this.container.getItem(i).isEmpty()) {
            ItemStack pane = new ItemStack(Items.DYE.gray());
            pane.set(DataComponents.CUSTOM_NAME, Component.literal(""));
            this.container.setItem(i, pane);
         }
      }
   }

   private static ItemStack betStack(String name, String color) {
      ItemStack s = new ItemStack(Items.EMERALD);
      s.set(DataComponents.CUSTOM_NAME, Component.literal(color + "§lChallenge " + name + " §lto Wager"));
      s.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Click, then type the amount"), Component.literal("§7Both players ante up,"), Component.literal("§7winner takes the pool!")
            )
         )
      );
      return s;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (slotId < 0 || slotId >= 9) {
         super.clicked(slotId, button, input, player);
      } else if (input != ContainerInput.PICKUP) {
         super.clicked(slotId, button, input, player);
      } else {
         ServerPlayer sp = (ServerPlayer)player;
         switch (slotId) {
            case 0:
               sp.closeContainer();
               Chat.msg(sp, "§7Type the wager amount in chat (e.g. §e100§7):");
               Chat.msg(sp, "§7Target: §c" + this.name0);
               DuelManager.setPendingSpectatorBet(sp.getUUID(), this.fighter0);
               break;
            case 1:
               sp.closeContainer();
               Chat.msg(sp, "§7Type the wager amount in chat (e.g. §e100§7):");
               Chat.msg(sp, "§7Target: §9" + this.name1);
               DuelManager.setPendingSpectatorBet(sp.getUUID(), this.fighter1);
               break;
            case 2:
            case 3:
            case 5:
            case 6:
            case 7:
            default:
               super.clicked(slotId, button, input, player);
               break;
            case 4:
               DuelManager.toggleSpectatorMode(sp);
               this.rebuild();
               break;
            case 8:
               sp.closeContainer();
               DuelManager.unspectate(sp);
         }
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }

   public boolean stillValid(Player player) {
      return true;
   }
}
