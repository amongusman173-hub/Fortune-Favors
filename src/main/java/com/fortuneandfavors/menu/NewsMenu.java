package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.ServerNewspaperManager;
import com.fortuneandfavors.util.GuiUtil;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

public class NewsMenu extends ChestMenu {
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public NewsMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private NewsMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new NewsMenu(syncId, inv), Component.literal("§6§lThe Server News")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 3, Items.STAINED_GLASS_PANE.yellow());
      if (!ServerNewspaperManager.isFresh()) {
         ServerNewspaperManager.publishNow();
      }
      List<String> lines = ServerNewspaperManager.latestLines();
      List<List<String>> sections = new ArrayList<>();
      List<String> current = null;
      for (String l : lines) {
         String plain = l.replaceAll("§[0-9a-fk-or]", "");
         if (plain.startsWith("TOP STORY") || plain.startsWith("ECONOMY") || plain.startsWith("BOUNTY BOARD") || plain.startsWith("DISCOVERY")) {
            current = new ArrayList<>();
            sections.add(current);
            current.add(l);
         } else if (current != null && !plain.startsWith("━━━") && !plain.startsWith("Published")) {
            current.add(l);
         }
      }
      ItemStack header = new ItemStack(Items.WRITTEN_BOOK);
      header.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lFortune & Favors Daily"));
      header.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal(ServerNewspaperManager.isFresh() ? "§aToday's edition ✓" : "§eToday's edition is brewing..."),
               Component.literal("§7Published fresh every midnight EST."),
               Component.literal("§7Click a section below for the stories,"),
               Component.literal("§7or read the whole paper in chat with §f/news§7.")
            )
         )
      );
      this.container.setItem(0, header);
      String[] sectionItems = {null, "§c§lTOP STORY", "§a§lECONOMY", "§b§lBOUNTY BOARD", "§5§lDISCOVERY"};
      for (int i = 0; i < sections.size() && i < 4; i++) {
         List<String> sec = sections.get(i);
         ItemStack card = new ItemStack(i == 0 ? Items.WITHER_SKELETON_SKULL : (i == 1 ? Items.GOLD_INGOT : (i == 2 ? Items.PAPER : Items.ENDER_PEARL)));
         card.set(DataComponents.CUSTOM_NAME, Component.literal(sectionItems[i + 1]));
         List<Component> lore = new ArrayList<>();
         for (int j = 1; j < sec.size() && j <= 6; j++) {
            lore.add(Component.literal(sec.get(j)));
         }
         card.set(DataComponents.LORE, new ItemLore(lore));
         this.container.setItem(11 + i * 2, card);
      }
      if (sections.isEmpty()) {
         this.container
            .setItem(
               13,
               this.named(new ItemStack(Items.PAPER), "§7No paper published yet", "§7The first edition lands at midnight EST.")
            );
      }
      this.container.setItem(26, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   private ItemStack named(ItemStack stack, String name, String... loreLines) {
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (loreLines.length > 0) {
         List<Component> lore = new ArrayList<>();
         for (String l : loreLines) {
            lore.add(Component.literal(l));
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
      if (player instanceof ServerPlayer sp) {
         if (slotId == 26) {
            this.returnCarried(sp);
            sp.closeContainer();
         } else {
            this.returnCarried(sp);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
