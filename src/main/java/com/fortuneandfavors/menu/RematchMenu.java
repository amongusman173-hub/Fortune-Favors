package com.fortuneandfavors.menu;

import com.fortuneandfavors.util.Chat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;

public class RematchMenu extends ChestMenu {
   private static final int SLOTS = 9;
   private static final Set<RematchMenu> OPEN = ConcurrentHashMap.newKeySet();
   private static final Map<UUID, Map<UUID, Boolean>> CHOICES = new ConcurrentHashMap<>();
   private static final Item GREEN_DYE = (Item)Items.DYE.green();
   private static final Item GRAY_DYE = (Item)Items.DYE.gray();
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final UUID otherUuid;

   private RematchMenu(int syncId, Inventory playerInventory, SimpleContainer container, UUID otherUuid) {
      super(MenuType.GENERIC_9x1, syncId, playerInventory, container, 1);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.otherUuid = otherUuid;
      OPEN.add(this);
      this.rebuild();
   }

   public static void open(ServerPlayer player, UUID otherUuid) {
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new RematchMenu(syncId, inv, new SimpleContainer(9), otherUuid), Component.literal("§6§lRematch?"))
      );
   }

   public static boolean bothWantRematch(UUID a, UUID b) {
      Map<UUID, Boolean> aChoices = CHOICES.get(a);
      Map<UUID, Boolean> bChoices = CHOICES.get(b);
      return aChoices != null && Boolean.TRUE.equals(aChoices.get(b)) && bChoices != null && Boolean.TRUE.equals(bChoices.get(a));
   }

   public static boolean isOpenFor(UUID uuid) {
      for (RematchMenu m : OPEN) {
         if (m.owner != null && m.owner.getUUID().equals(uuid)) {
            return true;
         }
      }

      return false;
   }

   public static boolean hasChosen(UUID uuid) {
      return CHOICES.containsKey(uuid);
   }

   public static boolean choseRematch(UUID uuid, UUID other) {
      Map<UUID, Boolean> choices = CHOICES.get(uuid);
      return choices != null && Boolean.TRUE.equals(choices.get(other));
   }

   public static void clearChoices(UUID a, UUID b) {
      CHOICES.remove(a);
      CHOICES.remove(b);
   }

   private static ItemStack yesStack() {
      ItemStack s = new ItemStack(GREEN_DYE);
      s.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lREMATCH"));
      s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Fight again with the same mode"))));
      return s;
   }

   private static ItemStack noStack() {
      ItemStack s = new ItemStack(GRAY_DYE);
      s.set(DataComponents.CUSTOM_NAME, Component.literal("§8§lNO REMATCH"));
      s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Return home"))));
      return s;
   }

   private void rebuild() {
      this.container.setItem(3, yesStack());
      this.container.setItem(5, noStack());

      for (int i = 0; i < 9; i++) {
         if (i != 3 && i != 5) {
            ItemStack filler = new ItemStack(Items.STAINED_GLASS_PANE.gray());
            filler.set(DataComponents.CUSTOM_NAME, Component.literal("§8 "));
            this.container.setItem(i, filler);
         }
      }

      this.broadcastChanges();
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId != 3 && slotId != 5) {
            super.clicked(slotId, button, input, sp);
         } else {
            boolean want = slotId == 3;
            CHOICES.computeIfAbsent(sp.getUUID(), k -> new HashMap<>()).put(this.otherUuid, want);
            this.rebuild();
            if (want) {
               ItemStack s = new ItemStack(GREEN_DYE);
               s.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l✔ REMATCH"));
               s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§aSelected!"))));
               this.container.setItem(3, s);
               Chat.msg(sp, "§aYou chose to rematch!");
            } else {
               ItemStack s = new ItemStack(GRAY_DYE);
               s.set(DataComponents.CUSTOM_NAME, Component.literal("§8§l✘ NO REMATCH"));
               s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§8Selected!"))));
               this.container.setItem(5, s);
               Chat.msg(sp, "§8You chose to go home.");
            }

            this.broadcastChanges();
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public void removed(Player player) {
      OPEN.remove(this);
      super.removed(player);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
