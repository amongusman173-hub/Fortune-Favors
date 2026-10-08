package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.BossCodexManager;
import com.fortuneandfavors.economy.BossCodexManager.BossEntry;
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

public class CodexMenu extends ChestMenu {
   private static final String[] BOSS_KEYS = {"king", "slime", "golem", "mind", "snow", "warden"};
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public CodexMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private CodexMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new CodexMenu(syncId, inv), Component.literal("§d§lBoss Codex")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 3, Items.STAINED_GLASS_PANE.purple());
      ItemStack header = new ItemStack(Items.BOOK);
      header.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lBoss Codex"));
      header.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Every raid boss ever encountered,"),
               Component.literal("§7and everything we know about them."),
               Component.literal("§8Slay bosses to fill the pages.")
            )
         )
      );
      this.container.setItem(0, header);
      for (int i = 0; i < BOSS_KEYS.length; i++) {
         String key = BOSS_KEYS[i];
         BossEntry e = BossCodexManager.all().get(key);
         ItemStack card = new ItemStack(
            switch (key) {
               case "king" -> Items.WITHER_SKELETON_SKULL;
               case "slime" -> Items.SLIME_BLOCK;
               case "golem" -> Items.STONE;
               case "mind" -> Items.ENDER_EYE;
               case "snow" -> Items.SNOW_BLOCK;
               default -> Items.SCULK;
            }
         );
         card.set(DataComponents.CUSTOM_NAME, Component.literal((e != null && e.kills > 0 ? "§e" : "§8") + BossCodexManager.displayName(key)));
         List<Component> lore = new ArrayList<>();
         if (e == null || e.firstDiscoveredBy.isEmpty()) {
            lore.add(Component.literal("§7Not yet discovered - go find this boss!"));
         } else {
            lore.add(Component.literal("§7First discovered: §f" + e.firstDiscoveredBy));
            lore.add(Component.literal("§7First defeated: §f" + e.firstDefeatedBy));
            lore.add(Component.literal("§7Total kills: §f" + e.kills));
            lore.add(Component.literal("§7Deaths caused: §c" + e.deathsCaused));
            if (e.fastestMs > 0L) {
               lore.add(Component.literal("§7Fastest kill: §b" + (e.fastestMs / 1000L) + "s §7by §f" + e.fastestBy));
            }
         }
         card.set(DataComponents.LORE, new ItemLore(lore));
         this.container.setItem(10 + i, card);
      }
      this.container.setItem(26, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
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
