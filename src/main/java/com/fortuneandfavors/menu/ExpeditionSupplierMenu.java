package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.ExpeditionManager;
import com.fortuneandfavors.economy.LootBackpack;
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

/**
 * The Expedition Supplier's shop.
 *
 * <p>Opened by right-clicking the supplier inside a site, and paid for out of SECURED loot - money
 * that is only yours if you walk out with it, which is what makes a purchase a real trade rather
 * than a free upgrade. The stock is a long table now: rations and light at the shallow end, kit
 * that changes a fight in the middle, and at the deep end the two things a run actually runs short
 * of - room to carry its loot home in, and time to still be in the site when it does.
 *
 * <p>Prices are not written on the shelf. Each line carries a base and is priced at the counter:
 * a line gets dearer every time this run buys it, everything gets dearer as the run's secured loot
 * grows, and the whole board drifts on the supplier's own two-minute tide. The window says which
 * way the tide is running and what it has already charged you, because a price the player cannot
 * read is a price that feels arbitrary.
 */
public class ExpeditionSupplierMenu extends ChestMenu {
   /** Row one is the header, rows two to four are the shelf, the last row is the counter. */
   private static final int INFO = 4;
   private static final int TIDE = 3;
   private static final int CLOSE = 53;
   private static final int SHELF_FIRST = 9;
   private static final int SHELF_LAST = 35;

   /** One line of stock: a key for its demand ledger, its icon, its base price and its pitch. */
   private record Offer(String key, ItemStack icon, long base, String name, String lore) {
   }

   private static final Offer[] OFFERS = {
      // Shallow end: the things a run runs out of first, priced so they are never a decision.
      offer("bread", new ItemStack(Items.BREAD, 8), 300, "§fBread x8", "§7Rations. Eight of them."),
      offer("beef", new ItemStack(Items.COOKED_BEEF, 8), 600, "§fCooked Beef x8", "§7Real food, real healing."),
      offer("torch", new ItemStack(Items.TORCH, 64), 250, "§fTorches x64", "§7The maze has corners the lanterns miss."),
      offer("arrows", new ItemStack(Items.ARROW, 32), 400, "§fArrows x32", "§7Ammo for anything you would rather not touch."),
      offer("blocks", new ItemStack(Items.COBBLESTONE, 64), 300, "§fCobblestone x64", "§7Bridge a gap, wall off a doorway, climb a wall."),
      offer("milk", new ItemStack(Items.MILK_BUCKET), 500, "§fMilk Bucket", "§7Purges poison, wither and the Blackout."),
      // Middle: kit that changes a fight.
      offer("pearl", new ItemStack(Items.ENDER_PEARL, 4), 1_200, "§5Ender Pearls x4", "§7Leave a fight you have already lost."),
      offer("water", new ItemStack(Items.WATER_BUCKET), 700, "§fWater Bucket", "§7Negates a fall, or a pool of lava."),
      offer("shield", new ItemStack(Items.SHIELD), 1_400, "§fShield", "§7The one defence expeditions actually sell."),
      offer("apple", new ItemStack(Items.GOLDEN_APPLE, 2), 1_500, "§6Golden Apples x2", "§7A heal you can eat mid-swing."),
      offer("tnt", new ItemStack(Items.TNT, 8), 1_800, "§cTNT x8", "§7Opens a room. Or a wall. Or you."),
      offer("dsword", new ItemStack(Items.DIAMOND_SWORD), 4_000, "§bDiamond Sword", "§7The floor guardian does not care how brave you are."),
      offer("dpick", new ItemStack(Items.DIAMOND_PICKAXE), 3_500, "§bDiamond Pickaxe", "§7Ore vaults pay for themselves - if you can mine them."),
      offer("dhelmet", new ItemStack(Items.DIAMOND_HELMET), 3_000, "§bDiamond Helmet", "§7Armour you can be pushed around in."),
      offer("dchest", new ItemStack(Items.DIAMOND_CHESTPLATE), 6_000, "§bDiamond Chestplate", "§7The piece that decides deep fights."),
      offer("dleggings", new ItemStack(Items.DIAMOND_LEGGINGS), 4_500, "§bDiamond Leggings", "§7Half a set is a different run."),
      offer("dboots", new ItemStack(Items.DIAMOND_BOOTS), 3_000, "§bDiamond Boots", "§7Falls, magma and long corridors."),
      // Deep end: the expensive answers.
      // The netherite ingot used to sit here, and it is gone on purpose: a run's payout is the only
      // thing this shop trades in, and an ingot was the one line that turned secured loot straight
      // back into raw progression - the same armour a mine would have given, bought with a site the
      // player never had to survive. The deep end sells what a run cannot dig for itself.
      offer("egapple", new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), 14_000, "§dEnchanted Golden Apple", "§7Absorption, resistance and a full heal."),
      // The pack patch, bought rather than found. It is the one line here that is deliberately
      // single-unit: a patch is a row of pack, three is a whole pack, and a shelf that sold them in
      // threes would make the find in a rich chest pointless. One per purchase, priced against the
      // Time Shard so carrying room and time stay a real decision.
      offer("pack_patch", LootBackpack.upgrader(), 9_000, LootBackpack.UPGRADER_NAME, "§7Stitches §f9 more pieces§7 into the pack you carry."),
      offer("shard", ExpeditionManager.timeShard(), 8_000, "§d§lTime Shard", "§7Right-click for §d+45 seconds§7 on this site."),
      offer("shard2", ExpeditionManager.timeShard(), 16_000, "§d§lTime Shard (deep stock)", "§7The same shard, and the tide is against you.")
   };

   private final SimpleContainer container;
   private final ServerPlayer owner;

   public ExpeditionSupplierMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54));
   }

   private ExpeditionSupplierMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider(
         (syncId, inv, p) -> new ExpeditionSupplierMenu(syncId, inv),
         Component.literal("§a§lExpedition Supplier")
      ));
   }

   private static Offer offer(String key, ItemStack icon, long base, String name, String lore) {
      return new Offer(key, icon, base, name, lore);
   }

   /**
    * Every line of stock as the counter would show it, in shelf order.
    *
    * <p>For the self-test and the audit, so that what the shelf actually offers is checked against
    * what it is documented to offer rather than against a copy of the list kept somewhere else. A
    * line that was removed from the array and left in a comment, or a line whose icon quietly became
    * a stack, is a shelf nobody looks at until a player is standing in front of it.
    */
   public static List<ItemStack> stockedIcons() {
      List<ItemStack> out = new java.util.ArrayList<>();
      for (Offer line : OFFERS) {
         out.add(line.icon().copy());
      }
      return out;
   }

   private static int shelfSlot(int index) {
      return SHELF_FIRST + index;
   }

   private ItemStack priced(int index) {
      Offer offer = OFFERS[index];
      long cost = ExpeditionManager.supplierPrice(this.owner.getUUID(), offer.key(), offer.base());
      ItemStack stack = offer.icon().copy();
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(offer.name()));
      stack.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal(offer.lore()),
         Component.literal("§7Price now: §a" + Chat.moneyStr(cost)),
         Component.literal("§8Base " + Chat.moneyStr(offer.base()) + " - the counter prices it."),
         Component.literal("§8Each purchase of a line raises that line.")
      )));
      return stack;
   }

   /** Redraws the whole shelf at today's prices - nothing about a price is stored in the menu. */
   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 6, Items.STAINED_GLASS_PANE.green());
      for (int i = 0; i < OFFERS.length && shelfSlot(i) <= SHELF_LAST; i++) {
         this.container.setItem(shelfSlot(i), this.priced(i));
      }
      this.container.setItem(CLOSE, named(new ItemStack(Items.BARRIER), "§cClose"));
      this.refreshInfo();
      this.broadcastChanges();
   }

   private void refreshInfo() {
      double tide = ExpeditionManager.supplierTide(this.owner.getUUID());
      ItemStack info = new ItemStack(Items.GOLD_NUGGET);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lSupplier Shop"));
      long loot = ExpeditionManager.lootOf(this.owner.getUUID());
      info.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Supplies are bought with §asecured expedition loot§7."),
         Component.literal("§7Spending here is spending your payout - "),
         Component.literal("§7you only keep it if you walk out with it."),
         Component.literal("§6Secured loot available: §a" + Chat.moneyStr(loot)),
         Component.literal("§8Prices rise with every purchase of a line,"),
         Component.literal("§8and again as a run's secured loot grows.")
      )));
      this.container.setItem(INFO, info);

      ItemStack tideItem = new ItemStack(Items.CLOCK);
      tideItem.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lSupplier's Tide"));
      tideItem.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal(
            tide < 0.95 ? "§aRunning cheap: §f-§a" + Math.round((1.0 - tide) * 100.0) + "%§7 on everything."
               : tide > 1.05 ? "§cRunning dear: §f+§c" + Math.round((tide - 1.0) * 100.0) + "%§7 on everything."
               : "§7Running level - neither cheap nor dear."
         ),
         Component.literal("§8The board drifts every two minutes."),
         Component.literal("§8Nothing here is ever a free upgrade.")
      )));
      this.container.setItem(TIDE, tideItem);
   }

   private static ItemStack named(ItemStack stack, String name) {
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
         if (slotId >= SHELF_FIRST && slotId <= SHELF_LAST && slotId - SHELF_FIRST < OFFERS.length) {
            this.returnCarried(sp);
            this.buy(sp, slotId - SHELF_FIRST);
            return;
         }
         this.returnCarried(sp);
         this.broadcastChanges();
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private void buy(ServerPlayer sp, int index) {
      if (!ExpeditionManager.isInExpedition(sp.getUUID())) {
         Chat.msg(sp, "&eYou're not on an expedition anymore - get back in there first!");
         sp.closeContainer();
         return;
      }
      Offer offer = OFFERS[index];
      long cost = ExpeditionManager.supplierPrice(sp.getUUID(), offer.key(), offer.base());
      if (!ExpeditionManager.spendLoot(sp.getUUID(), cost)) {
         Chat.msg(sp, "&eCan't afford that at " + Chat.moneyStr(cost) + " - secure more loot first!");
         SoundUtil.play(sp, ModSounds.DENY);
         return;
      }
      ExpeditionManager.supplierBought(sp.getUUID(), offer.key());
      ItemStack give = offer.icon().copy();
      // One of whatever the shelf is selling, always: a counter is a shop, not a stack of free
      // stock, and a line whose icon happens to be worth eight would otherwise pay out eight.
      give.setCount(1);
      give.set(DataComponents.LORE, null);
      give.set(DataComponents.CUSTOM_NAME, null);
      InventoryHelper.giveOrDrop(sp, give);
      SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
      Chat.msg(
         sp, "&aBought " + offer.name() + " &afor &f" + Chat.moneyStr(cost)
            + "&a - secured loot left: &f" + Chat.moneyStr(ExpeditionManager.lootOf(sp.getUUID()))
      );
      this.rebuild();
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
