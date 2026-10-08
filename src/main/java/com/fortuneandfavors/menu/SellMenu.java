package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.BlockValues;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.SkillManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.ItemOriginals;
import com.fortuneandfavors.util.SoundUtil;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

public class SellMenu extends ChestMenu {
   private static final int SELL_START = 0;
   private static final int UI_BALANCE = 45;
   private static final int UI_RECEIVE = 46;
   private static final int UI_TOTAL = 47;
   private static final int UI_CLOSE = 53;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final ItemOriginals originals = new ItemOriginals();

   private static boolean isGlassPane(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
         return id.equals("minecraft:glass_pane") || id.endsWith("_stained_glass_pane");
      } else {
         return false;
      }
   }

   private long sellValue(ItemStack stack) {
      return isGlassPane(stack) ? 12L * stack.getCount() : BlockValues.valueOf(stack);
   }

   public SellMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54));
   }

   private SellMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuildUi();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new SellMenu(syncId, inv), Component.literal("§2§lSell Items")));
   }

   private boolean isUiSlot(int slot) {
      return slot == 45 || slot == 46 || slot == 47 || slot == 53 || slot == 48 || slot == 50 || slot == 51 || slot == 52;
   }

   private ItemStack infoStack(ItemStack base, String name, String lore) {
      ItemStack stack = base.copy();
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      return stack;
   }

   private ItemStack frame(Item item) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      return stack;
   }

   private void buildHeader() {
   }

   private void rebuildUi() {
      this.container.clearContent();
      this.buildHeader();
      this.container
         .setItem(45, this.infoStack(new ItemStack(Items.GOLD_INGOT), "§aYour balance", "§7" + Chat.moneyStr(EconomyManager.balance(this.owner.getUUID()))));
      float mult = SkillManager.sellMultiplier(this.owner.getUUID());
      this.container
         .setItem(46, this.infoStack(new ItemStack(Items.EMERALD), "§eYou receive", "§7Cash · values from block_values.json\n§7Sell rate: §f" + mult + "x"));
      this.container.setItem(47, this.infoStack(new ItemStack(Items.PAPER), "§fTotal value", "§a$0"));
      this.container.setItem(48, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.container.setItem(50, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.container.setItem(51, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.container.setItem(52, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
      this.container.setItem(53, this.infoStack(new ItemStack(Items.BARRIER), "§cClose", "§7Closing also sells what's in the window"));
   }

   private long sellableTotal() {
      long total = 0L;
      float mult = SkillManager.sellMultiplier(this.owner.getUUID()) * (float)com.fortuneandfavors.economy.ServerDisasterManager.sellMultiplier();

      for (int i = 0; i < 54; i++) {
         if (!this.isUiSlot(i)) {
            ItemStack s = this.container.getItem(i);
            if (!s.isEmpty()) {
               total += Math.round((float)this.sellValue(s) * mult);
            }
         }
      }

      return total;
   }

   private void refreshTotal() {
      for (int i = 0; i < 54; i++) {
         if (!this.isUiSlot(i)) {
            ItemStack s = this.container.getItem(i);
            if (s.isEmpty()) {
               this.originals.forget(i);
            } else {
               ItemStack real = this.originals.get(i, s);
               this.originals.remember(i, real);
               ItemStack display = real.copy();
               long worth = Math.round((float)this.sellValue(real) * SkillManager.sellMultiplier(this.owner.getUUID()));
               display.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Value: §a$" + worth))));
               this.container.setItem(i, display);
            }
         }
      }

      this.container.setItem(47, this.infoStack(new ItemStack(Items.PAPER), "§fTotal value", "§a$" + this.sellableTotal()));
      this.broadcastChanges();
   }

   private void clearCarried() {
      this.setCarried(ItemStack.EMPTY);
      this.setRemoteCarried(HashedStack.EMPTY);
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId == 53) {
            sp.closeContainer();
         } else if (slotId < 0 || slotId >= 54) {
            super.clicked(slotId, button, input, player);
         } else if (!this.isUiSlot(slotId)) {
            if (input == ContainerInput.QUICK_MOVE) {
               ItemStack original = this.originals.take(slotId, this.container.getItem(slotId));
               this.container.setItem(slotId, ItemStack.EMPTY);
               if (!original.isEmpty()) {
                  InventoryHelper.giveOrDrop(sp, original);
               }

               this.refreshTotal();
            } else if (input == ContainerInput.PICKUP || input == ContainerInput.SWAP) {
               ItemStack original = this.originals.take(slotId, this.container.getItem(slotId));
               super.clicked(slotId, button, input, player);
               if (!original.isEmpty()) {
                  if (input == ContainerInput.PICKUP) {
                     this.setCarried(ItemStack.EMPTY);
                     this.setRemoteCarried(HashedStack.EMPTY);
                     InventoryHelper.giveOrDrop(sp, original);
                  } else if (button >= 0 && button < 9) {
                     sp.getInventory().setItem(button, original);
                  }
               }

               this.refreshTotal();
            }
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      if (!(player instanceof ServerPlayer sp)) {
         return ItemStack.EMPTY;
      } else if (index >= 0 && index < 54) {
         if (this.isUiSlot(index)) {
            return ItemStack.EMPTY;
         }

         ItemStack original = this.originals.take(index, this.container.getItem(index));
         this.container.setItem(index, ItemStack.EMPTY);
         if (!original.isEmpty()) {
            InventoryHelper.giveOrDrop(sp, original);
         }

         this.refreshTotal();
         return ItemStack.EMPTY;
      } else {
         if (index < 54) {
            return ItemStack.EMPTY;
         }

         Slot slot = this.getSlot(index);
         ItemStack stack = slot.getItem();
         if (!stack.isEmpty()) {
            for (int i = 0; i < 54; i++) {
               if (!this.isUiSlot(i)) {
                  ItemStack target = this.container.getItem(i);
                  if (target.isEmpty()) {
                     int move = Math.min(stack.getCount(), stack.getMaxStackSize());
                     this.container.setItem(i, stack.copyWithCount(move));
                     stack.shrink(move);
                     slot.set(stack);
                     if (stack.isEmpty()) {
                        break;
                     }
                  } else {
                     ItemStack real = this.originals.get(i, target);
                     if (ItemStack.isSameItemSameComponents(real, stack) && real.getCount() < real.getMaxStackSize()) {
                        int move = Math.min(real.getMaxStackSize() - real.getCount(), stack.getCount());
                        real.grow(move);
                        stack.shrink(move);
                        slot.set(stack);
                        if (stack.isEmpty()) {
                           break;
                        }
                     }
                  }
               }
            }

            this.refreshTotal();
            this.broadcastChanges();
         }

         return ItemStack.EMPTY;
      }
   }

   public void removed(Player player) {
      super.removed(player);
      if (player instanceof ServerPlayer sp) {
         long total = 0L;
         boolean any = false;
         float mult = SkillManager.sellMultiplier(sp.getUUID()) * (float)com.fortuneandfavors.economy.ServerDisasterManager.sellMultiplier();

         for (int i = 0; i < 54; i++) {
            if (!this.isUiSlot(i)) {
               ItemStack s = this.container.getItem(i);
               if (!s.isEmpty()) {
                  long value = this.sellValue(s);
                  if (value > 0L) {
                     total += Math.round((float)value * mult);
                     any = true;
                     // Hand the stack to the contract board as well as the money: a standing order
                     // is about the goods, and only this loop knows which goods left.
                     com.fortuneandfavors.economy.DynamicContractsManager.onSellItem(sp, s);
                  } else {
                     InventoryHelper.giveOrDrop(sp, this.originals.get(i, s).copy());
                  }

                  this.originals.forget(i);
                  this.container.setItem(i, ItemStack.EMPTY);
               }
            }
         }

         if (any) {
            EconomyManager.addCash(sp.getUUID(), total);
            SoundUtil.play(sp, ModSounds.SELL);
            Chat.raw(sp, "§aSold items for " + Chat.moneyStr(total));
            com.fortuneandfavors.economy.DailyWeeklyChallengeManager.onSell(sp, total);
            com.fortuneandfavors.economy.DynamicContractsManager.onSell(sp, total);
            com.fortuneandfavors.guild.GuildManager.addWealth(sp.getUUID(), total);
         }
      }
   }
}
