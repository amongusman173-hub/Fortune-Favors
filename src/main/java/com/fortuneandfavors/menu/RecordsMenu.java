package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.FirstEverRecordManager;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

public class RecordsMenu extends ChestMenu {
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public RecordsMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private RecordsMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new RecordsMenu(syncId, inv), Component.literal("§5§lFirst Ever Records")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 3, Items.STAINED_GLASS_PANE.purple());
      ItemStack header = new ItemStack(Items.DRAGON_HEAD);
      header.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lFirst Ever Records"));
      header.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Permanent history of the server's"),
               Component.literal("§7greatest firsts - they can never"),
               Component.literal("§7be taken away. Go make history!"),
               Component.literal("§8" + FirstEverRecordManager.all().size() + " record(s) carved into stone")
            )
         )
      );
      this.container.setItem(0, header);
      Map<String, String> records = FirstEverRecordManager.all();
      List<String> keys = new ArrayList<>(records.keySet());
      if (keys.isEmpty()) {
         this.container
            .setItem(
               13,
               this.named(
                  new ItemStack(Items.PAPER),
                  "§7No records yet",
                  "§7Be the first to slay a boss,",
                  "§7reach a million, or spot a mythic!"
               )
            );
      } else {
         int i = 0;
         for (String key : keys) {
            if (i >= 21) {
               break;
            }
            ItemStack card = new ItemStack(iconFor(key));
            card.set(DataComponents.CUSTOM_NAME, Component.literal("§5★ " + FirstEverRecordManager.displayForNews(key)));
            card.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Achieved by §f" + records.get(key)))));
            this.container.setItem(2 + i, card);
            i++;
         }
      }
      this.container.setItem(26, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   private static net.minecraft.world.item.Item iconFor(String key) {
      if (key.startsWith("first_boss_")) {
         return switch (key.substring("first_boss_".length())) {
            case "king" -> Items.WITHER_SKELETON_SKULL;
            case "slime" -> Items.SLIME_BLOCK;
            case "golem" -> Items.STONE;
            case "mind" -> Items.ENDER_EYE;
            case "snow" -> Items.SNOW_BLOCK;
            case "warden" -> Items.SCULK;
            default -> Items.SKELETON_SKULL;
         };
      }
      return switch (key) {
         case "first_millionaire" -> Items.GOLD_BLOCK;
         case "first_mythic" -> Items.NETHER_STAR;
         default -> Items.WRITABLE_BOOK;
      };
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
