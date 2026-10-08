package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.VfxManager;
import com.fortuneandfavors.economy.MysteryChestManager;
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

public class MysteryChestMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int CLOSE = 35;
   private static final int[] TIER_SLOTS = {10, 11, 12, 13};
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public MysteryChestMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(36));
   }

   private MysteryChestMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x4, syncId, playerInventory, container, 4);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new MysteryChestMenu(syncId, inv), Component.literal("§d§lMystery Chests")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 4, Items.STAINED_GLASS_PANE.magenta());
      ItemStack info = new ItemStack(Items.ENDER_CHEST);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lMystery Chests"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Four tiers of loot - the higher the tier,"),
               Component.literal("§7the better the prizes."),
               Component.literal("§7Keys drop from bosses, streaks and challenges."),
               Component.literal("§7Click a chest to open it with a matching key."),
               Component.literal("§8Your keys: §f" + countKeys())
            )
         )
      );
      this.container.setItem(INFO, info);
      for (int tier = 0; tier < 4; tier++) {
         ItemStack card = MysteryChestManager.previewIcon(tier);
         card.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Prize ranges:"),
                  Component.literal("§7  cash §a$" + cashRange(tier)),
                  Component.literal("§7  or a rare item drop"),
                  Component.literal("§8Key: " + MysteryChestManager.tierColor(tier) + MysteryChestManager.tierName(tier) + " Mystery Key"),
                  Component.literal(hasKey(tier) ? "§a✓ You have a key - click to open!" : "§8No key for this tier yet")
               )
            )
         );
         this.container.setItem(TIER_SLOTS[tier], card);
      }
      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   private int countKeys() {
      int count = 0;
      for (int i = 0; i < this.owner.getInventory().getContainerSize(); i++) {
         if (MysteryChestManager.keyTier(this.owner.getInventory().getItem(i)) >= 0) {
            count++;
         }
      }
      return count;
   }

   private boolean hasKey(int tier) {
      for (int i = 0; i < this.owner.getInventory().getContainerSize(); i++) {
         if (MysteryChestManager.keyTier(this.owner.getInventory().getItem(i)) == tier) {
            return true;
         }
      }
      return false;
   }

   private static String cashRange(int tier) {
      return switch (tier) {
         case 0 -> "500-2,500";
         case 1 -> "2,500-7,500";
         case 2 -> "10,000-30,000";
         default -> "40,000-100,000";
      };
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
         for (int tier = 0; tier < 4; tier++) {
            if (slotId == TIER_SLOTS[tier]) {
               this.returnCarried(sp);
               ItemStack key = null;
               for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
                  ItemStack stack = sp.getInventory().getItem(i);
                  if (MysteryChestManager.keyTier(stack) == tier) {
                     key = stack;
                     break;
                  }
               }
               if (key == null) {
                  Chat.msg(sp, "&cYou need a " + MysteryChestManager.tierName(tier) + " Mystery Key to open this chest.");
                  SoundUtil.play(sp, ModSounds.DENY);
               } else {
                  MysteryChestManager.useKey(sp, key);
                  VfxManager.celebrate(sp);
                  this.rebuild();
               }
               return;
            }
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
