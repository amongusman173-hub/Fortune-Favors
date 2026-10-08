package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.BountyManager;
import com.fortuneandfavors.economy.BountyManager.Bounty;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import net.minecraft.world.item.component.ResolvableProfile;
import java.util.List;
import java.util.UUID;
import java.util.Map.Entry;
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

public class BountyMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int CLOSE = 22;
   private static final int LIST_START = 9;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public BountyMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private BountyMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new BountyMenu(syncId, inv), Component.literal("§6§lBounty Board")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 3, Items.STAINED_GLASS_PANE.red());
      List<Entry<UUID, Bounty>> all = new ArrayList<>(BountyManager.all().entrySet());
      all.sort((a, b) -> Long.compare(b.getValue().amount, a.getValue().amount));
      long total = 0L;
      for (Entry<UUID, Bounty> e : all) {
         total += e.getValue().amount;
      }
      ItemStack info = new ItemStack(Items.PAPER);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lBounty Board"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7" + all.size() + " active bounty" + (all.size() == 1 ? "" : "ies") + " - §e" + Chat.moneyStr(total) + "§7 pooled."),
               Component.literal("§7Slay a target to claim their bounty."),
               Component.literal("§7Biggest head is sorted to the top."),
               Component.literal("§8Place one: /bounty <player> <amount>"),
               Component.literal("§8Cancel yours: /bounty <player> cancel")
            )
         )
      );
      this.container.setItem(4, info);
      ServerPlayer wanted = BountyManager.highestBountyPlayer(this.owner.level().getServer());
      int slot = 9;

      for (Entry<UUID, Bounty> e : all) {
         if (slot >= 22) {
            break;
         }

         Bounty b = e.getValue();
         ItemStack head = new ItemStack(Items.PLAYER_HEAD);
         ServerPlayer target = this.owner.level().getServer() == null ? null : this.owner.level().getServer().getPlayerList().getPlayer(b.target);
         if (target != null) {
            head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(target.getGameProfile()));
         }
         boolean mostWanted = wanted != null && wanted.getUUID().equals(b.target);
         head.set(DataComponents.CUSTOM_NAME, Component.literal((mostWanted ? "§4§l☠ " : "§c☠ ") + b.targetName + " §7- " + Chat.moneyStr(b.amount)));
         head.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Posted by §f" + b.posterName),
                  Component.literal(target != null ? "§aOnline now - watch your back!" : "§7Offline - they'll be back."),
                  Component.literal(mostWanted ? "§4§lMOST WANTED§r §8- carries the biggest head" : "§8Kill them to collect the reward")
               )
            )
         );
         this.container.setItem(slot, head);
         slot++;
      }

      this.container.setItem(22, this.named(new ItemStack(Items.BARRIER), "§cClose"));
   }

   private ItemStack named(ItemStack base, String name) {
      ItemStack stack = base.copy();
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
         if (slotId == 22) {
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
