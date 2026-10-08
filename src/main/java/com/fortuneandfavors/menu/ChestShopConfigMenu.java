package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.ChestShopManager;
import com.fortuneandfavors.economy.ChestShopManager.ChestShop;
import com.fortuneandfavors.menu.ChestShopConfigMenu.PromptMode;
import com.fortuneandfavors.menu.ChestShopConfigMenu.ShopPromptMenu;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import java.util.HashMap;
import java.util.UUID;
import com.fortuneandfavors.util.PriceUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.fortuneandfavors.ModSounds;

public class ChestShopConfigMenu extends ChestMenu {
   private static final int TYPE = 0;
   private static final int CURRENCY = 1;
   private static final int DEFAULT_PRICE = 2;
   private static final int OPEN_CHEST = 3;
   private static final int DELETE = 4;
   private static final int LEDGER = 5;
   private static final int ALERTS = 6;
   private static final int LIST_START = 9;
   private static final int LIST_END = 35;
   private static final int CLOSE = 53;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final ChestBlockEntity chest;
   private final ChestShop shop;
   /** Two-step delete: the first click arms, the second one deletes. Any other
    *  click disarms, so a stray click can never destroy a shop. */
   private boolean deleteArmed;

   public ChestShopConfigMenu(int syncId, Inventory playerInventory, ChestBlockEntity chest, ChestShop shop) {
      this(syncId, playerInventory, chest, shop, new SimpleContainer(54));
   }

   private ChestShopConfigMenu(int syncId, Inventory playerInventory, ChestBlockEntity chest, ChestShop shop, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.chest = chest;
      this.shop = shop;
      this.rebuild();
   }

   public static void open(ServerPlayer player, ChestBlockEntity chest, ChestShop shop) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestShopConfigMenu(syncId, inv, chest, shop), Component.literal("§2§lShop Settings")));
   }

   private void rebuild() {
      this.container.clearContent();
      String type = this.shop.type == null ? ChestShopManager.TYPE_BUY : this.shop.type;
      boolean buy = ChestShopManager.TYPE_BUY.equals(type);
      boolean sell = ChestShopManager.TYPE_SELL.equals(type);
      this.container
         .setItem(
            0,
            this.button(
               buy ? Items.EMERALD : (sell ? Items.GOLD_INGOT : Items.BARRIER),
               buy ? "§a§lMODE: BUY SHOP" : (sell ? "§e§lMODE: SELL SHOP" : "§c§lMODE: CLOSED"),
               buy ? "§7Players pay to take items from this chest." : (sell ? "§7Players sell their items into this chest." : "§7Closed: nobody can trade here, but prices,"),
               buy ? "" : (sell ? "" : "§7stock and the chest's protection are kept."),
               "§8Also: §f/chestshop toggle§8 on the shop chest",
               "§8Click to cycle: buy §8-> §8sell §8-> §8closed §8-> §8buy"
            )
         );
      this.container
         .setItem(
            1,
            this.button(
               Items.DIAMOND,
               "§b§lCURRENCY",
               "§7Payment: §f" + (this.shop.isItemCurrency() ? this.currencyName() : "Cash ($)"),
               "§8Click to change (item id or \"cash\")"
            )
         );
      this.container
         .setItem(
            2,
            this.button(
               Items.PAPER,
               "§f§lDEFAULT PRICE",
               "§7Current: " + Chat.moneyStr(this.shop.priceFor(Items.DIAMOND)),
               "§8Click to change the fallback price for items"
            )
         );
      this.container.setItem(3, this.button(Items.CHEST, "§e§lOPEN CHEST", "§7Stock items and take out what you're owed", "§8Click to open the chest"));
      this.container
         .setItem(
            LEDGER,
            this.button(
               Items.BOOK,
               "§6§lSALES LEDGER",
               "§7See what sells and when: the newest trades,",
               "§7what each customer bought, and the totals",
               "§7per item (Top Sellers).",
               "§8Click to open"
            )
         );
      this.container
         .setItem(
            ALERTS,
            this.button(
               this.shop.restockAlerts ? Items.BELL : Items.STICK,
               this.shop.restockAlerts ? "§a§lRESTOCK ALERTS: ON" : "§c§lRESTOCK ALERTS: OFF",
               "§7When a buy shop sells its last item of a line,",
               "§7you get a chat warning so you can restock.",
               "§8Click to turn " + (this.shop.restockAlerts ? "off" : "on") + "."
            )
         );
      this.container
         .setItem(
            4,
            this.deleteArmed
               ? this.button(
                  Items.LAVA_BUCKET,
                  "§4§lCONFIRM DELETE?",
                  "§cThis removes the shop from this chest.",
                  "§7Prices, currency and the closed/buy/sell setting",
                  "§7are lost. The chest itself and everything in it",
                  "§7stay exactly where they are.",
                  "§8Click again to confirm, or click anywhere else to cancel."
               )
               : this.button(
                  Items.BARRIER,
                  "§c§lDELETE SHOP",
                  "§7Stops this chest being a shop and forgets",
                  "§7its prices. The chest keeps its contents.",
                  "§8Click twice to confirm."
               )
         );
      List<ItemStack> stock = new ArrayList<>();

      for (int i = 0; i < this.chest.getContainerSize(); i++) {
         ItemStack s = this.chest.getItem(i);
         if (!s.isEmpty()) {
            stock.add(s);
         }
      }

      for (int i = 0; i < stock.size() && 9 + i <= 35; i++) {
         ItemStack s = stock.get(i).copy();
         s.setCount(1);
         Item item = s.getItem();
         Identifier id = BuiltInRegistries.ITEM.getKey(item);
         String key = id == null ? "*" : id.toString();
         boolean override = this.shop.prices.containsKey(key);
         s.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal(
                     "§7Price: §a" + (override ? (Serializable)this.shop.prices.get(key) : "default") + (override ? "" : " (" + this.shop.priceFor(item) + ")")
                  ),
                  Component.literal("§7Stock: §f" + stock.get(i).getCount()),
                  Component.literal("§8Click to set this item's price")
               )
            )
         );
         this.container.setItem(9 + i, s);
      }

      this.container.setItem(53, this.button(Items.BARRIER, "§cClose", ""));
   }

   private ItemStack button(Item item, String name, String... lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      // Blank entries are dropped: a varargs call that passes "" for a line it
      // does not need should not render as an empty gap in the tooltip.
      List<String> lines = new ArrayList<>();
      for (String line : lore) {
         if (line != null && !line.isEmpty()) {
            lines.add(line);
         }
      }

      if (!lines.isEmpty()) {
         stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(String.join("\n", lines)))));
      }

      return stack;
   }

   private String currencyName() {
      if (this.shop.isTokenCurrency()) {
         return "Token";
      }

      Item item = this.shop.currencyItem();
      return item != null && item != Items.AIR ? new ItemStack(item).getHoverName().getString() : "?";
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
         if (slotId >= 54) {
            super.clicked(slotId, button, input, player);
         } else {
            // Any click that is not the delete button cancels an armed delete,
            // so "confirm" always means the very next click on that button.
            if (slotId != DELETE && this.deleteArmed) {
               this.deleteArmed = false;
               this.rebuild();
               this.broadcastChanges();
            }

            switch (slotId) {
               case 0:
                  this.shop.type = ChestShopManager.nextType(this.shop.type);
                  this.save();
                  Chat.msg(sp, "&aShop mode: &f" + ChestShopManager.typeName(this.shop.type)
                     + (ChestShopManager.TYPE_OFF.equals(this.shop.type)
                        ? " &7- nobody can trade here until you reopen it."
                        : " &7- customers see the change immediately."));
                  this.rebuild();
                  this.broadcastChanges();
                  return;
               case 1:
                  this.returnCarried(sp);
                  this.prompt(sp, PromptMode.CURRENCY, null);
                  return;
               case 2:
                  this.returnCarried(sp);
                  this.prompt(sp, PromptMode.DEFAULT_PRICE, null);
                  return;
               case 3:
                  sp.openMenu(new SimpleMenuProvider((syncId, inv, p) -> ChestMenu.threeRows(syncId, inv, this.chest), Component.literal("§2Shop chest")));
                  return;
               case LEDGER:
                  this.returnCarried(sp);
                  ShopLedgerMenu.open(sp, this.chest, this.shop);
                  return;
               case ALERTS:
                  this.returnCarried(sp);
                  this.shop.restockAlerts = !this.shop.restockAlerts;
                  this.save();
                  Chat.msg(sp, this.shop.restockAlerts
                     ? "&aRestock alerts on. &7You'll be warned when a line sells out."
                     : "&cRestock alerts off. &7The ledger still records every sale.");
                  SoundUtil.play(sp, ModSounds.PAGE_FLIP);
                  this.rebuild();
                  this.broadcastChanges();
                  return;
               case DELETE:
                  if (!this.deleteArmed) {
                     this.deleteArmed = true;
                     this.rebuild();
                     this.broadcastChanges();
                     return;
                  }

                  this.returnCarried(sp);
                  ChestShopManager.delete(ChestShopManager.keyFor(this.owner.level(), this.chest.getBlockPos()), sp.level().getServer());
                  Chat.msg(sp, "&cShop deleted. &7The chest is an ordinary chest again.");
                  SoundUtil.play(sp, ModSounds.PAGE_FLIP);
                  sp.closeContainer();
                  return;
               case 53:
                  this.returnCarried(sp);
                  sp.closeContainer();
                  return;
               default:
                  if (slotId >= 9 && slotId <= 35) {
                     Item item = this.stockItemAt(slotId - 9);
                     if (item != null) {
                        this.returnCarried(sp);
                        this.prompt(sp, PromptMode.ITEM_PRICE, item);
                     }
                  } else {
                     this.returnCarried(sp);
                  }
            }
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private Item stockItemAt(int index) {
      int seen = 0;

      for (int i = 0; i < this.chest.getContainerSize(); i++) {
         ItemStack s = this.chest.getItem(i);
         if (!s.isEmpty()) {
            if (seen == index) {
               return s.getItem();
            }

            seen++;
         }
      }

      return null;
   }

   private void save() {
      ChestShopManager.save(this.owner.level().getServer());
   }

   private void prompt(ServerPlayer player, PromptMode mode, Item item) {
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new ShopPromptMenu(syncId, inv, this.chest, this.shop, mode, item), Component.literal("§2§lShop settings"))
      );
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }


    enum PromptMode {
       CURRENCY,
       DEFAULT_PRICE,
       ITEM_PRICE;
    }

    static class ShopPromptMenu extends AnvilPromptMenu {
       private final ChestShop shop;
       private final PromptMode mode;
       private final Item item;
    
       ShopPromptMenu(
          int syncId, Inventory playerInventory, ChestBlockEntity chest, ChestShop shop, PromptMode mode, Item item
       ) {
          super(syncId, playerInventory, new ItemStack(Items.PAPER), helper("§eShop setting", new String[]{switch (mode) {
             case CURRENCY -> "§7Type an item id (minecraft:diamond) or §fcash§7.";
             case DEFAULT_PRICE -> "§7Type a price, e.g. §a500§7, §a10k§7.";
             case ITEM_PRICE -> "§7Type a price for §f" + (item == null ? "this item" : new ItemStack(item).getHoverName().getString()) + "§7: §a10k§7, §a250§7...";
          }}));
          this.shop = shop;
          this.mode = mode;
          this.item = item;
          this.returnInputOnCancel = false;
       }
    
       protected boolean canAccept(String text) {
          return switch (this.mode) {
             case CURRENCY -> !text.isEmpty();
             default -> PriceUtil.parse(text) > 0L;
          };
       }
    
       protected void renderResult(ItemStack result, String text) {
          if (this.mode == PromptMode.CURRENCY) {
             result.set(DataComponents.CUSTOM_NAME, Component.literal(text.isEmpty() ? "§8Type \"cash\" or an item id" : "§aSet currency to §f" + text));
          } else {
             long price = PriceUtil.parse(text);
             result.set(DataComponents.CUSTOM_NAME, Component.literal(price > 0L ? "§aSet price to " + Chat.moneyStr(price) : "§cType a price"));
          }
       }
    
       protected void accept(ServerPlayer player, String text) {
          switch (this.mode) {
             case CURRENCY:
                String clean = text.trim().toLowerCase();
                if ("cash".equals(clean)) {
                   this.shop.currency = "cash";
                   Chat.msg(player, "&aShop now pays/receives cash.");
                } else if ("token".equals(clean)) {
                   this.shop.currency = "fortuneandfavors:token";                    Chat.msg(player, "&aShop now trades in favor tokens.");
                } else {
                   Identifier id = Identifier.tryParse(clean.contains(":") ? clean : "minecraft:" + clean);
                   if (id == null || BuiltInRegistries.ITEM.getValue(id) == Items.AIR) {
                      Chat.msg(player, "&cUnknown item: &f" + text);
                      return;
                   }
    
                   this.shop.currency = id.toString();
                   Chat.msg(player, "&aShop currency set to &f" + new ItemStack(BuiltInRegistries.ITEM.getValue(id)).getHoverName().getString());
                }
                break;
             case DEFAULT_PRICE: {
                long price = PriceUtil.parse(text);
                this.shop.prices.put("*", price);
                Chat.msg(player, "&aDefault price set to " + Chat.moneyStr(price));
                break;
             }
             case ITEM_PRICE: {
                long price = PriceUtil.parse(text);
                if (this.item != null) {
                   this.shop.prices.put(BuiltInRegistries.ITEM.getKey(this.item).toString(), price);
                   Chat.msg(player, "&aPrice set for &f" + new ItemStack(this.item).getHoverName().getString() + "&a: " + Chat.moneyStr(price));
                }
             }
          }
    
          ChestShopManager.save(player.level().getServer());
          SoundUtil.play(player, ModSounds.PAGE_FLIP);
       }
    
       protected void reopen(ServerPlayer player) {
       }
    }
}
