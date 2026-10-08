package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.Advancements;
import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Manage GUI for a placed chair: shows the owner, offers pickup, and lets
 *  the owner restyle the chair into any wood stair variant (the placed block
 *  changes; the machine record stays). Non-owners can look but not touch. */
public class ChairMenu extends ChestMenu {
   private static final int OWNER = 4;
   private static final int PICKUP = 11;
   private static final int VARIANTS_START = 18; // row 3: 9 wood variants
   private static final int CLOSE = 22;
   private static final Block[] WOODS = {
      Blocks.OAK_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.BIRCH_STAIRS, Blocks.JUNGLE_STAIRS,
      Blocks.ACACIA_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.MANGROVE_STAIRS, Blocks.CHERRY_STAIRS, Blocks.PALE_OAK_STAIRS
   };
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final BlockPos pos;
   private final boolean isOwner;
   private boolean pickedUp = false;

   private ChairMenu(int syncId, Inventory playerInventory, BlockPos pos) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, new SimpleContainer(27), 3);
      this.container = (SimpleContainer)this.getContainer();
      this.owner = (ServerPlayer)playerInventory.player;
      this.pos = pos;
      MachineManager.Machine m = MachineManager.get(playerInventory.player.level(), pos);
      this.isOwner = m != null && (m.owner() == null || m.owner().equals(this.owner.getUUID()));
      this.rebuild();
   }

   public static void open(ServerPlayer player, ServerLevel level, BlockPos pos) {
      BlockPos frozen = pos.immutable();
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new ChairMenu(syncId, inv, frozen), Component.literal("§6§lChair"))
      );
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 3, Items.STAINED_GLASS_PANE.orange());

      MachineManager.Machine m = MachineManager.get(this.owner.level(), this.pos);
      ItemStack ownerCard = new ItemStack(Items.NAME_TAG);
      ownerCard.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lChair Owner"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Owner: §f" + (m == null ? "unknown" : (m.ownerName().isEmpty() ? m.owner().toString() : m.ownerName()))));
      lore.add(Component.literal("§7Location: §f" + this.pos.getX() + ", " + this.pos.getY() + ", " + this.pos.getZ()));
      lore.add(Component.literal("§8Break-proof: this chair can't be mined."));
      if (this.isOwner) {
         lore.add(Component.literal("§aYou own this chair."));
      } else {
         lore.add(Component.literal("§cOnly the owner can manage it."));
      }
      ownerCard.set(DataComponents.LORE, new ItemLore(lore));
      this.container.setItem(OWNER, ownerCard);

      if (this.isOwner) {
         ItemStack pick = new ItemStack(Items.SADDLE);
         pick.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lPick Up"));
         pick.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Take the chair back as an item"),
                  Component.literal("§7(it keeps whatever wood it is now)."),
                  Component.literal("§8Anyone sitting on it gets up first.")
               )
            )
         );
         this.container.setItem(PICKUP, pick);

         String[] names = {
            "§fOak", "§6Spruce", "§eBirch", "§aJungle", "§dAcacia", "§8Dark Oak", "§cMangrove", "§bCherry", "§fPale Oak"
         };
         for (int i = 0; i < WOODS.length; i++) {
            Block w = WOODS[i];
            ItemStack card = new ItemStack(w);
            Block placed = this.owner.level().getBlockState(this.pos).getBlock();
            boolean current = placed == w;
            List<Component> vlore = new ArrayList<>();
            vlore.add(Component.literal(current ? "§a✓ Current style" : "§7Click to restyle into " + names[i] + "§7."));
            vlore.add(Component.literal("§8Same seat, new wood - facing kept."));
            card.set(DataComponents.CUSTOM_NAME, Component.literal(names[i] + " Stair §8(chair)"));
            card.set(DataComponents.LORE, new ItemLore(vlore));
            if (current) {
               card.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            this.container.setItem(VARIANTS_START + i, card);
         }
      }

      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   private ItemStack named(ItemStack stack, String name, String... lines) {
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (lines.length > 0) {
         List<Component> lore = new ArrayList<>();
         for (String l : lines) {
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
         if (slotId == CLOSE) {
            this.returnCarried(sp);
            sp.closeContainer();
            return;
         }
         if (!this.isOwner) {
            this.returnCarried(sp);
            return;
         }
         if (slotId == PICKUP) {
            this.returnCarried(sp);
            if (com.fortuneandfavors.block.ChairBlock.pickUp(sp, sp.level(), this.pos)) {
               this.pickedUp = true;
               Advancements.grant(sp, "chair_sitter");
               sp.closeContainer();
            }
            this.rebuild();
            return;
         }
         if (slotId >= VARIANTS_START && slotId < VARIANTS_START + WOODS.length) {
            this.returnCarried(sp);
            Block want = WOODS[slotId - VARIANTS_START];
            if (sp.level().getBlockState(this.pos).getBlock() == want) {
               this.rebuild();
               return;
            }
            if (com.fortuneandfavors.block.ChairBlock.restyle((ServerLevel)sp.level(), this.pos, want)) {
               com.fortuneandfavors.util.Chat.msg(sp, "&7Chair restyled into &f" + want.getName().getString() + "&7.");
               Advancements.grant(sp, "interior_designer");
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

   public void removed(Player player) {
      if (player instanceof ServerPlayer sp && !this.pickedUp) {
         // Nothing lives in the container, so nothing to hand back - but keep
         // the contract with the base class in case that changes.
      }
      super.removed(player);
   }

   /** Accessor kept for symmetry with the other menus (unused today). */
   public boolean wasPickedUp() {
      return this.pickedUp;
   }

   private static ItemStack stackOf(Block b) {
      return b.asItem() instanceof BlockItem ? new ItemStack(b) : new ItemStack(Items.OAK_STAIRS);
   }
}
