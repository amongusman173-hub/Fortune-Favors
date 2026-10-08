package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.CustomEnchantments;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/** The \"What can I craft?\" reference - every custom recipe in one place:
 *  crafting-table recipes, Item Forge recipes, enchant fusion and runes. */
public class RecipeMenu extends ChestMenu {
   private static final int BACKPACK = 10;
   private static final int SHARD = 11;
   private static final int DISTANT_MEMORY = 12;
   private static final int TIER_UPGRADE = 13;
   private static final int TOME_FUSION = 14;
   private static final int RUNES = 15;
   private static final int REPAIR = 16;
   private static final int BUNDLE = 17;
   private static final int ENDER_POUCH = 18;
   private static final int CHAIR = 19;
   private static final int DEATH_COMPASS = 20;
   private static final int CLOSE = 22;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   private RecipeMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new RecipeMenu(syncId, inv, new SimpleContainer(27)), Component.literal("§6§lCustom Recipes")));
   }

   private void rebuild() {
      this.container.clearContent();
      this.container.setItem(4, this.card(
         new ItemStack(Items.CRAFTING_TABLE),
         "§eCrafting & Forging guide",
         "§7Every custom recipe in one place - what goes in,\n§7where, and what you get out."
      ));
      this.container.setItem(BACKPACK, this.card(
         ModItems.backpack(1),
         "§6Backpack",
         "§7Crafting Table: §f8 Leather + 1 String§7.\n§7Right-click to open 9 slots of storage.\n§7Upgrade in the Item Forge with Iron Ingots."
      ));
      this.container.setItem(SHARD, this.card(
         ModItems.distantMemoryShard(),
         "§dThe Echoing Shard Of A Distant Memory",
         "§7Crafting Table: §f4 Echo Shards + 1 Sculk Catalyst§7.\n§7The key ingredient for a Distant Memory sword."
      ));
      this.container.setItem(DISTANT_MEMORY, this.card(
         ModItems.distantMemorySword(),
         "§dDistant Memory Sword",
         "§7Item Forge: §fDiamond Sword + Echoing Shard§7.\n§7A blade of a world that no longer exists."
      ));
      this.container.setItem(TIER_UPGRADE, this.card(
         ModItems.witherBlade(),
         "§eLegendary Tier Upgrades",
         "§7Item Forge: legendary + its essence§7.\n§7Tier I→II costs §f1 essence§7, II→III costs §f2§7.\n§7Each boss family has its own essence."
      ));
      this.container.setItem(TOME_FUSION, this.card(
         CustomEnchantments.tome("ff_lifesteal", 3),
         "§dEnchant Tome Fusion",
         "§7Item Forge or anvil: weapon + Tome I-V.\n§7Life Steal · Sticky · Seismic · Mind Wrack · Frostbite"
      ));
      this.container.setItem(RUNES, this.card(
         ModItems.runeOfFlame(),
         "§dRune Socketing",
         "§7Hold a §fsword, axe or armor piece§7 in your main\n§7hand and the rune in your §foff hand§7, then right-click.\n§7Weapons: Haste · Flame · Fortune · Swiftness · Frost ·\n§7Reach · Lifesteal    Armor: Warding · Fortitude"
      ));
      this.container.setItem(REPAIR, this.card(
         ModItems.repairMembrane(),
         "§bRepair Membrane",
         "§7Item Forge: §fany damaged item + a membrane§7\n§7= full durability restored."
      ));
      this.container.setItem(BUNDLE, this.card(
         ModItems.bundle(1),
         "§bBundle",
         "§7Crafting Table: §f6 Leather + 1 String§7.\n§7Right-click to open 9 slots of storage.\n§7Item Forge: Leather → Iron → Netherite\n§7(more slots each tier)."
      ));
      this.container.setItem(ENDER_POUCH, this.card(
         ModItems.enderPouch(),
         "§5Ender Pouch",
         "§7Crafting Table: §f8 Obsidian + 1 Ender Pearl§7.\n§7Item Forge: §6Backpack§7 + pouch = §5Ender\n§7Backpack§7 - sneak-right-click the pack to\n§7open your ender chest anywhere."
      ));
      this.container.setItem(CHAIR, this.card(
         ModItems.chair(),
         "§6Chair",
         "§7Crafting Table: §f2 Oak Planks (vertical)§7.\n§7Right-click to sit down, sneak to get up.\n§7Sneak-right-click to pick it back up -\n§7it can't be broken, only reclaimed."
      ));
      this.container.setItem(DEATH_COMPASS, this.card(
         ModItems.deathCompass(),
         "§4Death Compass",
         "§7Crafting Table: §fcompass + 4 Soul Sand§7 (cross).\n§7Hold it to track your grave - right-click to\n§7ping the spot with a beacon. Goes quiet once\n§7you retrieve your grave. Also sold in the Gem Shop."
      ));
      this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose"));
   }

   private ItemStack named(Item item, String name) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return stack;
   }

   private ItemStack card(ItemStack icon, String name, String lore) {
      ItemStack stack = icon.copy();
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId == CLOSE) {
            sp.closeContainer();
            return;
         }
         if (slotId >= 0 && slotId < 27) {
            SoundUtil.play(sp, ModSounds.AUCTION_BID);
            return;
         }
      }
      super.clicked(slotId, button, input, player);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }

   public void removed(Player player) {
      super.removed(player);
      this.clearCarried();
   }

   private void clearCarried() {
      this.setCarried(ItemStack.EMPTY);
      this.setRemoteCarried(HashedStack.EMPTY);
   }
}
