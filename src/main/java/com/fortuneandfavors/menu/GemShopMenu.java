package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import com.fortuneandfavors.ModSounds;
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

public class GemShopMenu extends ChestMenu {
   private static final int CLOSE = 44;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public GemShopMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(45));
   }

   private GemShopMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x5, syncId, playerInventory, container, 5);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new GemShopMenu(syncId, inv), Component.literal("§5§lGem Shop")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 5, Items.STAINED_GLASS_PANE.purple());

      // Header with gem balance
      long gems = EconomyManager.gemBalance(this.owner.getUUID());
      ItemStack header = new ItemStack(Items.EMERALD);
      header.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lGem Shop"));
      header.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Your gems: §5" + gems),
         Component.literal("§7Earn gems from raids, challenges,"),
         Component.literal("§7contracts, and achievements."),
         Component.literal("§8Click an item to buy it with gems.")
      )));
      this.container.setItem(4, header);

      // Row 1: Mystery Key (5 gems) - display the REAL key item so its
      // name/lore show, and grant a real one on purchase.
      this.container.setItem(10, shopItem(com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(0), "§d§lMystery Key", 5));

      // The rest of the keys. There are four tiers of them and the shop sold only the
      // common one, so the ladder the keys exist to be climbed was stocked one rung at a
      // time and the climb happened somewhere else.
      this.container.setItem(24, shopItem(com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(1), "§d§lMystery Key II", 12));
      this.container.setItem(25, shopItem(com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(2), "§5§lMystery Key III", 25));
      this.container.setItem(28, shopItem(com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(3), "§6§lMystery Key IV", 50));

      // Row 1: Tag Color Reset (3 gems) - instantly resets your tag color.
      this.container.setItem(11, shopItem(Items.NAME_TAG, "§b§lTag Recolor", 3, "§7Instantly resets your tag", "§7color to the default green."));

      // Row 1: Raid Banner (10 gems)
      this.container.setItem(12, shopItem(Items.BANNER.red(), "§c§lRaid Banner", 10, "§7Start a Player Raid on demand."));

      // Row 1: Golden Apple x16 (4 gems)
      this.container.setItem(13, shopItem(Items.GOLDEN_APPLE, "§6§lGolden Apple x16", 4, "§716 golden apples for combat."));

      // Row 1: Enchanted Golden Apple x4 (15 gems)
      this.container.setItem(14, shopItem(Items.ENCHANTED_GOLDEN_APPLE, "§6§lEnch. Golden Apple x4", 15, "§74 enchanted golden apples."));

      // Row 1: Totem of Undying (12 gems)
      this.container.setItem(15, shopItem(Items.TOTEM_OF_UNDYING, "§e§lTotem of Undying", 12, "§7Cheat death once."));

      // Row 1: Death Compass (6 gems) - tracks your grave until you retrieve it.
      this.container.setItem(16, shopItem(ModItems.deathCompass(), "§4§lDeath Compass", 6));

      // Row 2: Netherite Ingot x2 (20 gems)
      this.container.setItem(19, shopItem(Items.NETHERITE_INGOT, "§5§lNetherite Ingot x2", 20, "§72 netherite ingots."));

      // Row 2: Repairing Membrane (8 gems) - repairs ANY item or armor to full in the Item Forge. Only 1 per purchase.
      //
      // The REAL membrane, not the vanilla phantom membrane it is built on. The old line
      // stocked the base item, so the shop sold "Repairing Membrane" and handed over a
      // plain phantom membrane: the right name, the right price, and an item the forge
      // does not recognise. Both the display and the reward come from this one call now.
      this.container.setItem(20, shopItem(ModItems.repairMembrane(), "§b§lRepairing Membrane", 8));

      // Row 2: Wither Skull x2 (18 gems)
      this.container.setItem(21, shopItem(Items.WITHER_SKELETON_SKULL, "§8§lWither Skull x2", 18, "§72 wither skeleton skulls."));

      // Row 2: Heart of the Sea (14 gems)
      this.container.setItem(22, shopItem(Items.HEART_OF_THE_SEA, "§d§lHeart of the Sea", 14, "§7Craft a conduit."));

      // Row 2: Shulker Shell x4 (16 gems)
      this.container.setItem(23, shopItem(Items.SHULKER_SHELL, "§d§lShulker Shell x4", 16, "§74 shulker shells."));

      this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose"));
      this.broadcastChanges();
   }

   /**
    * What the gem shop may never stock, name in its lore, or hand over.
    *
    * <p>"Not overpowered" is a rule about the shop as a whole rather than about the items
    * somebody happened to think of, so it is written here once and enforced at all three
    * places the shop can speak: what it draws, what it says, and what a purchase hands
    * over. An elytra off a gem shop makes every map in the mod optional, and a mace makes
    * the arena a joke; the second half of the requirement - "or says that" - is why the
    * lore is checked too. Pinned by {@code shop.clerk-never-sells-an-op-item}.
    */
   private static final List<Item> NEVER_STOCKED = List.of(
      Items.ELYTRA,
      Items.HEAVY_CORE,
      Items.MACE,
      Items.DRAGON_EGG
   );

   /** The rule, as a predicate, so the test can pin it without a clerk to talk to. */
   public static boolean shopMayStock(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return true;
      }

      for (Item item : NEVER_STOCKED) {
         if (stack.is(item)) {
            return false;
         }
      }

      return true;
   }

   /** The refusal, so the same sentence is said wherever the rule is broken. */
   public static String refusalFor(ItemStack stack) {
      return "the gem shop may not stock " + (stack == null || stack.isEmpty() ? "nothing" : stack.getHoverName().getString())
         + " - it is on the never-sold list";
   }

   /** For the self-test: the names of the rule, so the list cannot quietly empty out. */
   public static List<Item> neverStockedForTest() {
      return List.copyOf(NEVER_STOCKED);
   }

   private ItemStack shopItem(Item item, String name, int gemCost, String... loreLines) {
      ItemStack stack = new ItemStack(item);
      if (!shopMayStock(stack)) {
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.error("[FF] gem shop refused to stock {}: {}", name, refusalFor(stack));
         return this.named(Items.BARRIER, "§cUnavailable");
      }

      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      java.util.List<Component> lore = new java.util.ArrayList<>();
      for (String l : loreLines) {
         lore.add(Component.literal(l));
      }
      lore.add(Component.literal("§5Cost: " + gemCost + " gems"));
      lore.add(Component.literal("§8Click to purchase"));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      // Store gem cost in custom data
      var tag = new net.minecraft.nbt.CompoundTag();
      tag.putInt("gem_cost", gemCost);
      tag.putString("shop_name", name);
      stack.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
      return stack;
   }

   /** Overload for prebuilt (branded) stacks: keeps the item's own name/lore
    *  and just appends the gem cost + purchase hint. */
   private ItemStack shopItem(ItemStack base, String name, int gemCost) {
      if (!shopMayStock(base)) {
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.error("[FF] gem shop refused to stock {}: {}", name, refusalFor(base));
         return this.named(Items.BARRIER, "§cUnavailable");
      }

      ItemStack stack = base.copy();
      var existing = (ItemLore)stack.get(DataComponents.LORE);
      java.util.List<Component> lines = new java.util.ArrayList<>();
      if (existing != null) {
         lines.addAll(existing.lines());
      }
      lines.add(Component.literal("§5Cost: " + gemCost + " gems"));
      lines.add(Component.literal("§8Click to purchase"));
      stack.set(DataComponents.LORE, new ItemLore(lines));
      // MERGE into any existing custom data (a branded item's NBT, e.g. the
      // mystery key's tier tag) instead of replacing it.
      var existingData = (net.minecraft.world.item.component.CustomData)stack.get(DataComponents.CUSTOM_DATA);
      var tag = existingData != null ? existingData.copyTag() : new net.minecraft.nbt.CompoundTag();
      tag.putInt("gem_cost", gemCost);
      tag.putString("shop_name", name);
      stack.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
      return stack;
   }

   private ItemStack named(Item item, String name) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return stack;
   }

   /** Builds the REAL item for a purchase - branded mod items come from their
    *  proper constructors so they keep their NBT (the old code handed out a
    *  plain vanilla copy, which is how the Mystery Key arrived as a useless
    *  tripwire hook). */
   private ItemStack rewardFor(String name, ItemStack display) {
      int qty = 1;
      if (name.contains("x16")) qty = 16;
      else if (name.contains("x8")) qty = 8;
      else if (name.contains("x4")) qty = 4;
      else if (name.contains("x2")) qty = 2;

      if (name.contains("Mystery Key")) {
         // Four tiers of key exist; the roman numeral in the shelf label is what says which
         // one was bought, so the tier cannot be lost between the button and the reward.
         int tier = name.contains("IV") ? 3 : name.contains("III") ? 2 : name.contains("II") ? 1 : 0;
         return com.fortuneandfavors.economy.MysteryChestManager.mysteryKey(tier);
      }
      if (name.contains("Repairing Membrane")) {
         return ModItems.repairMembrane();
      }
      if (name.contains("Raid Banner")) {
         return ModItems.raidBanner();
      }
      if (name.contains("Death Compass")) {
         return ModItems.deathCompass();
      }
      if (name.contains("Tag Recolor")) {
         // Reset the player's tag color back to the default (milestone green).
         com.fortuneandfavors.economy.TagManager.Tag tag = com.fortuneandfavors.economy.TagManager.getTag(this.owner.getUUID());
         if (tag != null) {
            com.fortuneandfavors.economy.TagManager.setTag(this.owner.getUUID(), tag.text(), 5635925);
            com.fortuneandfavors.economy.TagManager.refreshTabList(this.owner.level().getServer());
            Chat.msg(this.owner, "&bYour tag color has been reset to the default green!");
         } else {
            Chat.msg(this.owner, "&7You don't have a tag set - set one with &f/tag set <text>&7 first.");
         }
         return ItemStack.EMPTY; // service purchase - nothing to hand over
      }

      // Plain vanilla items (apples, totems, netherite, skulls...): hand out
      // the display item's type in the advertised quantity.
      ItemStack plain = new ItemStack(display.getItem());
      plain.setCount(qty);
      return plain;
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(net.minecraft.network.HashedStack.EMPTY);
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
         if (slotId >= 0 && slotId < 45 && slotId != 4) {
            ItemStack display = this.container.getItem(slotId);
            if (display.isEmpty()) {
               this.returnCarried(sp);
               return;
            }
            var data = (net.minecraft.world.item.component.CustomData)display.get(DataComponents.CUSTOM_DATA);
            if (data == null) {
               this.returnCarried(sp);
               return;
            }
            int cost = data.copyTag().getInt("gem_cost").orElse(0);
            if (cost <= 0) {
               this.returnCarried(sp);
               return;
            }
            long gems = EconomyManager.gemBalance(sp.getUUID());
            if (gems < cost) {
               Chat.msg(sp, "&cYou need &5" + cost + " gems&c - you only have &5" + gems + "&c.");
               SoundUtil.play(sp, ModSounds.DENY);
               this.returnCarried(sp);
               return;
            }
            // Purchase! Everything is granted through a real constructor (never
            // a plain copy of the display item) so branded items keep their NBT.
            EconomyManager.takeGems(sp.getUUID(), cost);
            String name = data.copyTag().getString("shop_name").orElse("");
            ItemStack reward = rewardFor(name, display);
            // The last of the three places the rule is enforced, and the only one that
            // matters if the shelf is ever changed: a reward is checked before it is
            // handed over, and a refused one costs nothing (the gems go back).
            if (!shopMayStock(reward)) {
               EconomyManager.addGems(sp.getUUID(), cost);
               Chat.msg(sp, "&cThe gem shop cannot sell that (" + refusalFor(reward) + "). Nothing was charged.");
               com.fortuneandfavors.FortuneFavorsMod.LOGGER.error("[FF] gem shop refused to hand over {} for {}", name, refusalFor(reward));
               this.rebuild();
               return;
            }
            InventoryHelper.giveOrDrop(sp, reward);
            SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
            Chat.msg(sp, "&aPurchased &f" + name + "&a for &5" + cost + " gems&a!");
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
