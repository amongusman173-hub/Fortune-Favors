package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.TokenManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.List;
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

public class TokenMenu extends ChestMenu {
   private static final int CLOSE = 53;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public TokenMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54));
   }

   private TokenMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new TokenMenu(syncId, inv), Component.literal("§6§lFavor Tokens")));
   }

   private void rebuild() {
      this.container.clearContent();

      for (int i = 0; i < 4; i++) {
         int version = i + 1;
         ItemStack token = TokenManager.createToken(this.owner, version);
         token.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Price: §a$1000"),
                  Component.literal("§7Version " + TokenManager.versionName(version) + " - a separate token for a separate purpose"),
                  Component.literal("§8Click to buy one")
               )
            )
         );
         this.container.setItem(i, token);
      }

      ItemStack info = new ItemStack(Items.BOOK);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lWhat are tokens?"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A special currency you can give to friends."),
               Component.literal("§7Spend them at chest shops - set a shop's currency"),
               Component.literal("§7to a token and price things in tokens."),
               Component.literal("§8Only /token creates them - they can't be crafted or replicated.")
            )
         )
      );
      this.container.setItem(8, info);
      this.container.setItem(53, this.named(Items.BARRIER, "§cClose"));
   }

   private ItemStack named(Item item, String name) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId >= 54) {
            super.clicked(slotId, button, input, player);
         } else if (slotId == 53) {
            sp.closeContainer();
         } else if (slotId >= 0 && slotId < 4) {
            int version = slotId + 1;
            long price = 1000L;
            if (!EconomyManager.takeCash(sp.getUUID(), price)) {
               Chat.msg(sp, "&cNot enough money! A token costs " + Chat.moneyStr(price));
            } else {
               ItemStack token = TokenManager.createToken(sp, version);
               int given = InventoryHelper.giveOrDrop(sp, token);
               SoundUtil.play(sp, ModSounds.BUY);
               Chat.raw(
                  sp,
                  "&aBought a &fToken " + TokenManager.versionName(version) + "&a for " + Chat.moneyStr(price) + "&a. Give it to friends or spend it at shops."
               );
            }
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
