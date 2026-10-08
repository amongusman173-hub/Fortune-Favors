package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.economy.TokenManager;
import com.fortuneandfavors.economy.MachineManager.Machine;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.List;
import net.minecraft.core.BlockPos;
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

public class RedeemerMenu extends ChestMenu {
   private static final int TOKEN_I = 0;
   private static final int TOKEN_IV = 3;
   private static final int POOL = 4;
   private static final int DEPOSIT_1K = 5;
   private static final int DEPOSIT_10K = 6;
   private static final int DEPOSIT_100K = 7;
   private static final int WITHDRAW = 8;
   private static final int INFO = 13;
   private static final int CLOSE = 26;
   private static final long[] DEPOSITS = new long[]{1000L, 10000L, 100000L};
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final BlockPos pos;

   public RedeemerMenu(int syncId, Inventory playerInventory, BlockPos pos) {
      this(syncId, playerInventory, pos, new SimpleContainer(27));
   }

   private RedeemerMenu(int syncId, Inventory playerInventory, BlockPos pos, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.pos = pos;
      this.rebuild();
   }

   public static void open(ServerPlayer player, BlockPos pos) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new RedeemerMenu(syncId, inv, pos), Component.literal("§6§lToken Redeemer")));
   }

   private Machine machine() {
      Machine m = MachineManager.get(this.owner.level(), this.pos);
      return m != null && "token_redeemer".equals(m.type()) ? m : null;
   }

   private void rebuild() {
      this.container.clearContent();
      Machine m = this.machine();
      if (m == null) {
         this.container.setItem(13, this.named(Items.BARRIER, "§cRedeemer missing"));
      } else {
         boolean isOwner = m.owner() != null && m.owner().equals(this.owner.getUUID());

         for (int v = 1; v <= 4; v++) {
            ItemStack token = new ItemStack(TokenManager.versionItem(v));
            token.set(DataComponents.CUSTOM_NAME, Component.literal("§6Token " + TokenManager.versionName(v)));
            token.set(
               DataComponents.LORE,
               new ItemLore(
                  List.of(
                     Component.literal("§7Payout: §a" + Chat.moneyStr(m.payout(v))),
                     Component.literal(isOwner ? "§8Click to set the payout" : "§8Only the owner can change payouts")
                  )
               )
            );
            this.container.setItem(0 + v - 1, token);
         }

         ItemStack pool = new ItemStack(Items.EMERALD_BLOCK);
         pool.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lPool"));
         pool.set(
            DataComponents.LORE,
            new ItemLore(List.of(Component.literal("§7" + Chat.moneyStr(m.cash()) + " available"), Component.literal("§8Paid out when tokens are redeemed")))
         );
         this.container.setItem(4, pool);
         this.container.setItem(5, this.depositButton("§2+$1k", "Deposit into the pool", isOwner));
         this.container.setItem(6, this.depositButton("§2+$10k", "Deposit into the pool", isOwner));
         this.container.setItem(7, this.depositButton("§2+$100k", "Deposit into the pool", isOwner));
         this.container.setItem(8, this.named(isOwner ? Items.HOPPER : (Item)Items.DYE.gray(), isOwner ? "§eWithdraw all" : "§8Owner only"));
         ItemStack info = new ItemStack(Items.BOOK);
         info.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lHow it works"));
         info.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Right-click this block holding a token"),
                  Component.literal("§7to redeem it for its payout. Cash comes"),
                  Component.literal("§7from the pool - the owner funds it."),
                  Component.literal("§8Tokens are minted by /token and branded by"),
                  Component.literal("§8their creator (shift-right-click).")
               )
            )
         );
         this.container.setItem(13, info);
         this.container.setItem(26, this.named(Items.BARRIER, "§cClose"));
      }
   }

   private ItemStack depositButton(String name, String lore, boolean enabled) {
      return this.named(enabled ? Items.SUNFLOWER : (Item)Items.DYE.gray(), enabled ? name : "§8Owner only");
   }

   private ItemStack named(ItemStack base, String name, String lore) {
      ItemStack stack = base.copy();
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      return stack;
   }

   private ItemStack named(Item item, String name) {
      return this.named(new ItemStack(item), name, "");
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId >= 27) {
            super.clicked(slotId, button, input, player);
         } else if (slotId == 26) {
            this.returnCarried(sp);
            sp.closeContainer();
         } else {
            Machine m = this.machine();
            if (m == null) {
               this.returnCarried(sp);
               Chat.msg(sp, "&cThe redeemer is gone.");
               sp.closeContainer();
            } else if (slotId >= 0 && slotId <= 3) {
               int version = slotId - 0 + 1;
               if (m.owner() != null && m.owner().equals(sp.getUUID())) {
                  RedeemerPayoutMenu.open(sp, this.pos, version);
               } else {
                  Chat.msg(sp, "&cOnly the owner can change payouts.");
               }

               this.returnCarried(sp);
            } else if (slotId == 5 || slotId == 6 || slotId == 7) {
               MachineManager.deposit(sp, sp.level(), this.pos, DEPOSITS[slotId - 5]);
               this.rebuild();
               this.broadcastChanges();
               this.returnCarried(sp);
            } else if (slotId == 8) {
               MachineManager.withdraw(sp, sp.level(), this.pos);
               this.rebuild();
               this.broadcastChanges();
               this.returnCarried(sp);
            } else {
               this.returnCarried(sp);
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
