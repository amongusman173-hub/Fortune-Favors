package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.CCEnchantments;
import com.fortuneandfavors.economy.CustomEnchantments;
import com.fortuneandfavors.economy.ForgeOps;
import com.fortuneandfavors.menu.ItemForgeMenu.LockedSlot;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.Arrays;
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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import java.util.HashMap;
import net.minecraft.world.Container;

public class ItemForgeMenu extends ChestMenu {
   private static final int LEGENDARY_SLOT = 11;
   private static final int MATERIAL_SLOT = 13;
   private static final int TOME_SLOT = 15;
   private static final int FORGE_BUTTON = 29;
   private static final int RECYCLE_BUTTON = 31;
   private static final int ENCHANT_BUTTON = 33;
   private static final int INFO = 22;
   private static final int CLOSE = 53;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public ItemForgeMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54));
   }

   private ItemForgeMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.lockDecorativeSlots();
      this.rebuild();
   }

   private void lockDecorativeSlots() {
      for (int i = 0; i < 54; i++) {
         if (!this.isInputSlot(i)) {
            Slot old = (Slot)this.slots.get(i);
            this.slots.set(i, new LockedSlot(this.container, i, old.x, old.y));
         }
      }
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ItemForgeMenu(syncId, inv), Component.literal("§6§lItem Forge")));
   }

   private void rebuild() {
      this.container.clearContent();
      Item black = (Item)Items.STAINED_GLASS_PANE.black();
      Item gray = (Item)Items.STAINED_GLASS_PANE.gray();
      Item dark = (Item)Items.STAINED_GLASS_PANE.gray();
      Item gold = (Item)Items.STAINED_GLASS_PANE.orange();
      Item green = (Item)Items.STAINED_GLASS_PANE.green();
      Item purple = (Item)Items.STAINED_GLASS_PANE.purple();

      for (int g = 0; g < 9; g++) {
         this.container.setItem(g, this.frame(g != 2 && g != 4 && g != 6 ? black : gray));
      }

      this.container
         .setItem(
            2,
            this.label(
               gold,
               "§6§l◆ LEGENDARY",
               "§7The legendary item to upgrade.",
               "§8Wither gear: Staff / Blade / Crown",
               "§8Slime gear: Launcher / Shield / Boots",
               "§8Golem gear: Stone Staff / Fist / Stoneheart",
               "§8Mind gear: Staff / Mask / Shroud"
            )
         );
      this.container
         .setItem(
            4,
            this.label(
               green,
               "§a§l◆ MATERIAL",
               "§7The boss drop that fuels the forge.",
               "§5Wither Essence§7 for wither gear,",
               "§dMythical Gelatin§7 for slime gear,",
               "§6Golem Core§7 for stone gear,",
               "§5Shattered Mind§7 for mind gear,",
               "§6Raiders Item Upgrader§7 for raid gear."
            )
         );
      this.container
         .setItem(
            6,
            this.label(
               purple,
               "§d§l◆ ENCHANTMENT TOME",
               "§7Fuse a custom enchant on the item:",
               "§cLife Steal§7 / §aSticky§7 / §8Seismic§7 / §dMind Wrack§7,",
               "§7level I-III. Each raid boss drops its own."
            )
         );

      for (int g = 9; g < 18; g++) {
         this.container.setItem(g, this.frame(g != 9 && g != 17 ? dark : black));
      }

      this.container.setItem(11, ItemStack.EMPTY);
      this.container.setItem(13, ItemStack.EMPTY);
      this.container.setItem(15, ItemStack.EMPTY);

      for (int g = 18; g < 27; g++) {
         this.container.setItem(g, this.frame(g == 22 ? black : dark));
      }

      this.container
         .setItem(
            22,
            this.label(
               Items.BOOK,
               "§fHow the forge works",
               "§7Drop a legendary or weapon into the first slot",
               "§7(legendary + its boss's material = FORGE;",
               "§7raid legendaries + a §6Raiders Item Upgrader§7 = FORGE;",
               "§7any weapon + a tome = ENCHANT;",
               "§7any item or armor + a §bRepairing Membrane§7 = full repair).",
               "§7A base §fsword§7, §fbow§7 or §fmace§7 + a §5Heart of the End§7",
               "§7+ §55 Dragon Scales§7 = one of the End's three",
               "§7legendary weapons (Voidfang / Starfall / Enderheart).",
               "§7A §6Backpack§7 + 8 ingots of the next material",
               "§7forges up: Copper > Gold > Iron > Diamond >",
               "§7Netherite (more slots each tier). Backpack + a",
               "§fCrafting Table§7 = a built-in §6Workbench§7 button,",
               "§7Backpack + a §bJukebox§7 = the §bJukebox§7 button.",
               "§7A §bBundle§7 upgrades the same way:",
               "§7Leather > Iron > Netherite.",
               "§7Backpack + a §5Ender Pouch§7 = §5Ender Backpack§7",
               "§7(sneak-right-click to open your ender chest).",
               "§7Tier II = 1 material §8· §7Tier III = 2 more",
               "§8Click or shift-click - both work"
            )
         );

      for (int g = 27; g < 36; g++) {
         this.container.setItem(g, this.frame(g != 27 && g != 35 ? dark : black));
      }

      this.container.setItem(28, this.frame(gold));
      this.container.setItem(30, this.frame(gold));
      this.container
         .setItem(
            31,
            this.label(
               Items.GRINDSTONE,
               "§e§lRECYCLE",
               "§7Break a spare legendary back down",
               "§7into its boss material - a slime item",
               "§7yields Mythical Gelatin, a wither item",
               "§7yields Wither Essence. Higher tiers",
               "§7return more (I = 1, II = 2, III = 3)."
            )
         );
      this.container.setItem(32, this.frame(purple));
      this.container.setItem(34, this.frame(purple));
      this.container
         .setItem(
            29,
            this.label(
               Items.NETHERITE_INGOT,
               "§6§lFORGE",
               "§7Upgrade the legendary one tier.",
               "§7Better damage, charges, armor,",
               "§7cooldowns - and a bigger blaze",
               "§7with every successful craft"
            )
         );
      this.container
         .setItem(
            33,
            this.label(
               Items.ENCHANTED_BOOK,
               "§d§lENCHANT",
               "§7Fuse the tome's enchantment onto",
               "§7the weapon or legendary. A higher",
               "§7level overwrites a lower one."
            )
         );

      for (int g = 36; g < 45; g++) {
         this.container.setItem(g, this.frame(g == 40 ? black : dark));
      }

      this.container
         .setItem(
            40,
            this.label(
               Items.SMITHING_TABLE,
               "§6§lThe Item Forge",
               "§7A dedicated forge where boss drops",
               "§7become legend - one tier at a time.",
               "§8The blaze rewards a master smith"
            )
         );

      for (int g = 45; g < 54; g++) {
         this.container.setItem(g, this.frame(black));
      }

      this.container.setItem(53, this.label(Items.BARRIER, "§cClose", ""));
      this.broadcastChanges();
   }

   private ItemStack frame(Item item) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      return stack;
   }

   private ItemStack label(Item item, String name, String... lines) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(lines).<Component>map(s -> Component.literal(s)).toList()));
      return stack;
   }

   private boolean isInputSlot(int slotId) {
      return slotId == 11 || slotId == 13 || slotId == 15;
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
         if (slotId == 53) {
            this.returnCarried(sp);
            sp.closeContainer();
         } else if (slotId == 29) {
            this.forge(sp);
            this.returnCarried(sp);
         } else if (slotId == 33) {
            this.enchant(sp);
            this.returnCarried(sp);
         } else if (slotId == 31) {
            this.recycle(sp);
            this.returnCarried(sp);
         } else if (this.isInputSlot(slotId)) {
            super.clicked(slotId, button, input, player);
         } else if (slotId >= 0 && slotId < 54) {
            this.returnCarried(sp);
         } else {
            super.clicked(slotId, button, input, player);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private void forge(ServerPlayer player) {
      ItemStack legendary = this.container.getItem(11);
      // The material is looked for in either of the two remaining input slots: a
      // boss drop dragged one slot too far right used to make FORGE do nothing at
      // all, which is the same "I put it in and nothing happened" shape as the
      // tome above. The tome slot is only consulted when it is not holding a tome.
      // Every non-tome stack in the two remaining input slots goes to the forge, not just
      // the first one: the End's three weapons are assembled from a base weapon, a Heart and
      // Dragon Scales, which cannot fit in a single slot, and a forge that only ever saw one
      // of them would refuse the craft no matter how the player arranged the bench.
      List<ItemStack> materials = new java.util.ArrayList<>();
      for (int slot : new int[]{MATERIAL_SLOT, TOME_SLOT}) {
         ItemStack stack = this.container.getItem(slot);
         if (stack != null && !stack.isEmpty() && !CustomEnchantments.isTome(stack) && !CCEnchantments.isCCTome(stack)) {
            materials.add(stack);
         }
      }
      ItemStack result = ForgeOps.forge(player, legendary, materials);
      if (!result.isEmpty()) {
         this.container.setItem(11, result);
         this.broadcastChanges();
      }
   }

   /**
    * Fuses the tome onto the item. The two are looked for across all three input
    * slots rather than read from a fixed pair, because the buttons and the slots
    * are not the same thing to a player: a tome dragged into the first or second
    * slot, or an item dropped into the tome slot, used to be read as "no item" or
    * "no tome" - the click did nothing at all, the book stayed exactly where it
    * was put, and from the outside that is "the forge gave me my book back and
    * never enchanted the item".
    *
    * <p>Whatever it finds, the enchanted item lands in the first slot and the
    * consumed tome's slot is explicitly cleared and re-synced, so the client can
    * never be left drawing a book the server has already eaten.
    */
   private void enchant(ServerPlayer player) {
      int itemSlot = -1;
      int tomeSlot = -1;
      for (int slot : new int[]{LEGENDARY_SLOT, MATERIAL_SLOT, TOME_SLOT}) {
         ItemStack stack = this.container.getItem(slot);
         if (stack == null || stack.isEmpty()) {
            continue;
         }
         boolean tome = CustomEnchantments.isTome(stack) || CCEnchantments.isCCTome(stack);
         if (tome) {
            if (tomeSlot < 0) {
               tomeSlot = slot;
            }
         } else if (itemSlot < 0 && ForgeOps.isEnchantableTarget(stack)) {
            itemSlot = slot;
         }
      }

      if (itemSlot < 0 || tomeSlot < 0) {
         // Nothing to say beyond the operation's own message - but say it, rather
         // than leaving the click silent.
         ForgeOps.enchant(player, itemSlot < 0 ? ItemStack.EMPTY : this.container.getItem(itemSlot), tomeSlot < 0 ? ItemStack.EMPTY : this.container.getItem(tomeSlot));
         return;
      }

      ItemStack item = this.container.getItem(itemSlot);
      ItemStack tome = this.container.getItem(tomeSlot);
      ItemStack result = ForgeOps.enchant(player, item, tome);
      if (result.isEmpty()) {
         // The operation refused and has already explained why. Put everything
         // back exactly where the player left it - a refused fuse must not shuffle
         // their arrangement.
         return;
      }

      this.container.setItem(itemSlot, ItemStack.EMPTY);
      if (tomeSlot != itemSlot) {
         this.container.setItem(tomeSlot, ItemStack.EMPTY);
      } else {
         // Both were the same slot only if the player somehow had a stack that is
         // both - leave nothing behind either way.
         this.container.setItem(tomeSlot, ItemStack.EMPTY);
      }
      this.container.setItem(LEGENDARY_SLOT, result);
      this.broadcastChanges();
   }

   private void recycle(ServerPlayer player) {
      ItemStack legendary = this.container.getItem(11);
      ItemStack cores = ForgeOps.recycle(player, legendary);
      if (!cores.isEmpty()) {
         this.container.setItem(11, cores);
         this.broadcastChanges();
      }
   }

   private int targetSlotFor(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return -1;
      } else if (ModItems.isBackpack(stack)
         || ModItems.isBundle(stack)
         || ModItems.isForgeLegendary(stack)
         || ModItems.isAnyEnderLegendary(stack)
         || ForgeOps.isEnchantableTarget(stack)) {
         // A forge legendary belongs in the target slot even when it is not an
         // "enchantable target" (the Slime Boots are unbreakable, so they are
         // not damageable) - without this they could not be shift-clicked into
         // the forge at all, only dragged by hand.
         return 11;
      } else if (ModItems.isForgeMaterial(stack)
         || ModItems.isHeartOfTheEnd(stack)
         || ModItems.isDragonScale(stack)
         || ModItems.isRepairMembrane(stack)
         || ModItems.isEnderPouch(stack)
         || isBackpackIngot(stack)
         || ModItems.isRune(stack)
         || stack.is(Items.CRAFTING_TABLE)
         || stack.is(Items.JUKEBOX)
         // The two kitchen machines: a Portable Furnace is a blast furnace item and a Portable
         // Campfire is a smoker item, and either of them fuses into a backpack.
         || stack.is(Items.BLAST_FURNACE)
         || stack.is(Items.SMOKER)) {
         // Two material slots, and the End's recipes need both of them at once: a base weapon
         // goes in the target slot, then a Heart of the End AND five Dragon Scales. Every
         // material used to be routed to the same slot, so a player who shift-clicked a Heart
         // and then the Scales watched the second stack refuse to move - which, from the bench,
         // is exactly "the heart and the scales don't fit in the forge". Give the first empty
         // (or matching) material slot instead, so both stacks seat themselves.
         return this.materialSlotFor(stack);
      } else if (CustomEnchantments.isTome(stack) || CCEnchantments.isCCTome(stack)) {
         return 15;
      } else {
         // Soulbind can be applied to any non-book item. Unknown items are
         // accepted into the target slot and the application path rejects every
         // other tome that is not compatible with the item.
         return ForgeOps.isSoulbindTarget(stack) ? 11 : -1;
      }
   }

   /**
    * Where a material belongs: the first of the two material slots that can take it.
    *
    * <p>Slot {@link #MATERIAL_SLOT} first, then {@link #TOME_SLOT} only when the first is holding a
    * different item - so a Heart and a stack of Scales, which together are one recipe, can both be
    * placed by shift-click. A slot holding the same item is preferred over an empty one, so a
    * second shift-click tops the stack up rather than splitting the same material across two slots.
    */
   private int materialSlotFor(ItemStack stack) {
      for (int slot : new int[]{MATERIAL_SLOT, TOME_SLOT}) {
         ItemStack in = this.container.getItem(slot);
         if (ItemStack.isSameItemSameComponents(in, stack) && in.getCount() < in.getMaxStackSize()) {
            return slot;
         }
      }
      for (int slot : new int[]{MATERIAL_SLOT, TOME_SLOT}) {
         if (this.container.getItem(slot).isEmpty()) {
            return slot;
         }
      }
      // Both taken by other things: the first slot is the honest answer, and the craft already
      // says what is wrong with the arrangement when the stack does not move.
      return MATERIAL_SLOT;
   }

   private static boolean isBackpackIngot(ItemStack stack) {
      return stack.is(Items.GOLD_INGOT) || stack.is(Items.IRON_INGOT) || stack.is(Items.DIAMOND) || stack.is(Items.NETHERITE_INGOT);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      if (player instanceof ServerPlayer sp) {
         if (this.isInputSlot(index)) {
            ItemStack stack = this.container.getItem(index);
            if (!stack.isEmpty()) {
               this.container.setItem(index, ItemStack.EMPTY);
               InventoryHelper.giveOrDrop(sp, stack);
               this.broadcastChanges();
            }

            return ItemStack.EMPTY;
         } else {
            if (index >= 54) {
               Slot slot = this.getSlot(index);
               ItemStack stack = slot.getItem();
               int target = this.targetSlotFor(stack);
               if (target >= 0) {
                  ItemStack in = this.container.getItem(target);
                  if (in.isEmpty()) {
                     int move = Math.min(stack.getCount(), stack.getMaxStackSize());
                     this.container.setItem(target, stack.copyWithCount(move));
                     stack.shrink(move);
                     slot.set(stack);
                  } else if (ItemStack.isSameItemSameComponents(in, stack) && in.getCount() < in.getMaxStackSize()) {
                     int move = Math.min(in.getMaxStackSize() - in.getCount(), stack.getCount());
                     in.grow(move);
                     stack.shrink(move);
                     slot.set(stack);
                  }

                  this.broadcastChanges();
               }
            }

            return ItemStack.EMPTY;
         }
      } else {
         return ItemStack.EMPTY;
      }
   }

   public void removed(Player player) {
      if (player instanceof ServerPlayer sp) {
         for (int g : new int[]{11, 13, 15}) {
            ItemStack s = this.container.getItem(g);
            if (!s.isEmpty()) {
               this.container.setItem(g, ItemStack.EMPTY);
               InventoryHelper.giveOrDrop(sp, s);
            }
         }

         ItemStack carried = this.getCarried();
         if (!carried.isEmpty()) {
            this.setCarried(ItemStack.EMPTY);
            this.setRemoteCarried(HashedStack.EMPTY);
            InventoryHelper.giveOrDrop(sp, carried);
         }
      }

      super.removed(player);
   }


    static final class LockedSlot extends Slot {
       LockedSlot(Container container, int slot, int x, int y) {
          super(container, slot, x, y);
       }
    
       public boolean mayPlace(ItemStack stack) {
          return false;
       }
    
       public boolean mayPickup(Player player) {
          return false;
       }
    }
}
