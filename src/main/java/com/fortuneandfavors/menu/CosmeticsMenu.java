package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.CosmeticManager;
import com.fortuneandfavors.economy.CosmeticManager.Cosmetic;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
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

public class CosmeticsMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int CLOSE = 35;
   private static final int TITLES_START = 10;
   private static final int COLORS_START = 19;
   private static final int TRAILS_START = 28;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public CosmeticsMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(36));
   }

   private CosmeticsMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x4, syncId, playerInventory, container, 4);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new CosmeticsMenu(syncId, inv), Component.literal("§d§lCosmetics")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 4, Items.STAINED_GLASS_PANE.pink());
      ItemStack info = new ItemStack(Items.NAME_TAG);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lCosmetics"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Titles, name colors and particle trails."),
               Component.literal("§7Cosmetics drop from Epic and Legendary"),
               Component.literal("§7Mystery Chests and stay with you forever."),
               Component.literal("§7Click a cosmetic you own to equip or unequip it."),
               Component.literal("§8Owned: §f" + CosmeticManager.ownedSet(this.owner.getUUID()).size() + "§8 / " + CosmeticManager.ALL.size())
            )
         )
      );
      this.container.setItem(INFO, info);
      int t = 0;
      int c = 0;
      int tr = 0;
      for (Cosmetic cos : CosmeticManager.ALL) {
         int slot = switch (cos.kind()) {
            case "title" -> TITLES_START + (t++);
            case "color" -> COLORS_START + (c++);
            default -> TRAILS_START + (tr++);
         };
         this.container.setItem(slot, this.card(cos));
      }
      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   private ItemStack card(Cosmetic cos) {
      boolean owned = CosmeticManager.has(this.owner.getUUID(), cos.key());
      String equipped = CosmeticManager.equipped(this.owner.getUUID(), cos.kind());
      boolean isEquipped = cos.key().equals(equipped);
      ItemStack stack = new ItemStack(owned ? cos.icon() : Items.GLASS_BOTTLE);
      stack.set(
         DataComponents.CUSTOM_NAME,
         Component.literal((owned ? (isEquipped ? "§a§l" : "§f") : "§8") + cos.display())
      );
      String section = switch (cos.kind()) {
         case "title" -> "§7Title";
         case "color" -> "§7Name color";
         default -> "§7Particle trail";
      };
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal(section + (cos.rare() ? " §6§lRARE" : "")),
               Component.literal("§7" + cos.desc()),
               Component.literal(
                  owned
                     ? (isEquipped ? "§a✓ Equipped - click to unequip" : "§8Click to equip")
                     : "§8Locked - open Epic/Legendary Mystery Chests"
               )
            )
         )
      );
      return stack;
   }

   private ItemStack named(ItemStack stack, String name) {
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

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId == CLOSE) {
            this.returnCarried(sp);
            sp.closeContainer();
            return;
         }
         int idx = -1;
         if (slotId >= TITLES_START && slotId < TITLES_START + 5) {
            idx = slotId - TITLES_START;
         } else if (slotId >= COLORS_START && slotId < COLORS_START + 4) {
            idx = 5 + (slotId - COLORS_START);
         } else if (slotId >= TRAILS_START && slotId < TRAILS_START + 5) {
            idx = 9 + (slotId - TRAILS_START);
         }
         if (idx >= 0 && idx < CosmeticManager.ALL.size()) {
            this.returnCarried(sp);
            String err = CosmeticManager.equip(sp, CosmeticManager.ALL.get(idx).key());
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               SoundUtil.play(sp, ModSounds.DENY);
            } else {
               SoundUtil.play(sp, ModSounds.TRANSFER);
            }
            this.rebuild();
            return;
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
