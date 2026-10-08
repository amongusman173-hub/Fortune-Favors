package com.fortuneandfavors.economy;

import com.fortuneandfavors.economy.ChestShopManager.ChestShop;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

public final class ChestShopManager {
   public static final String CURRENCY_CASH = "cash";
   public static final String CURRENCY_TOKEN = "fortuneandfavors:token";
   /** Trading modes. OFF keeps the record (and the chest's protection) but stops
    *  all trading, so a shop can be parked without losing its prices and stock. */
   public static final String TYPE_BUY = "buy";
   public static final String TYPE_SELL = "sell";
   public static final String TYPE_OFF = "off";
   /** Ledger kinds, from the owner's point of view: a customer BOUGHT out of a
    *  buy shop, or SOLD into a sell shop. */
   public static final String LEDGER_SOLD = "sold";
   public static final String LEDGER_BOUGHT = "bought";
   /** How many trades one shop remembers. Bounded so a busy shop's save file
    *  cannot grow without limit. */
   public static final int LEDGER_LIMIT = 60;
   /** How long two identical out-of-stock alerts are kept apart, so one item
    *  emptying cannot turn into a wall of chat. */
   private static final long ALERT_COOLDOWN_MS = 5L * 60L * 1000L;
   private static final Map<String, Long> lastAlertAt = new HashMap<>();
   private static long lastSaveAt;
   private static final Map<String, ChestShop> shops = new HashMap<>();
   private static Path dataFile;

   private ChestShopManager() {
   }

   public static String keyFor(String dimension, BlockPos pos) {
      return dimension + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
   }

   public static String keyFor(Level level, BlockPos pos) {
      return keyFor(level.dimension().identifier().toString(), pos);
   }

   public static void load(MinecraftServer server) {
      shops.clear();
      Provider access = server.registryAccess();
      dataFile = EconomyManager.getDataDir(server).resolve("chestshops.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("shops") && root.get("shops").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("shops").entrySet()) {
            try {
               JsonObject obj = e.getValue().getAsJsonObject();
               ChestShop shop = new ChestShop();
               shop.type = JsonUtil.jsonString(obj, "type", "buy");
               shop.owner = UUID.fromString(obj.get("owner").getAsString());
               shop.currency = JsonUtil.jsonString(obj, "currency", "cash");
               shop.restockAlerts = JsonUtil.jsonBool(obj, "restock_alerts", true);
               if (obj.has("prices") && obj.get("prices").isJsonObject()) {
                  for (Entry<String, JsonElement> p : obj.getAsJsonObject("prices").entrySet()) {
                     shop.prices.put(p.getKey(), p.getValue().getAsLong());
                  }
               }

               if (obj.has("ledger") && obj.get("ledger").isJsonArray()) {
                  for (JsonElement lineElement : obj.getAsJsonArray("ledger")) {
                     if (!lineElement.isJsonObject()) {
                        continue;
                     }

                     try {
                        shop.ledger.add(LedgerEntry.fromJson(lineElement.getAsJsonObject()));
                     } catch (Exception var11) {
                     }
                  }
               }

               shops.put(e.getKey(), shop);
            } catch (Exception var9) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("chestshops.json");
      }

      Provider access = server.registryAccess();
      JsonObject root = new JsonObject();
      JsonObject shopsObj = new JsonObject();

      for (Entry<String, ChestShop> e : shops.entrySet()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("type", e.getValue().type);
         obj.addProperty("owner", e.getValue().owner.toString());
         obj.addProperty("currency", e.getValue().currency);
         obj.addProperty("restock_alerts", e.getValue().restockAlerts);
         JsonObject prices = new JsonObject();

         for (Entry<String, Long> p : e.getValue().prices.entrySet()) {
            prices.addProperty(p.getKey(), p.getValue());
         }

         obj.add("prices", prices);
         JsonArray ledger = new JsonArray();

         for (LedgerEntry entry : e.getValue().ledger) {
            ledger.add(entry.toJson());
         }

         obj.add("ledger", ledger);
         shopsObj.add(e.getKey(), obj);
      }

      root.add("shops", shopsObj);
      JsonUtil.write(dataFile, root);
   }

   public static ChestShop get(String key) {
      return shops.get(key);
   }

   public static ChestShop get(Level level, BlockPos pos) {
      ChestShop shop = shops.get(keyFor(level, pos));
      if (shop != null) {
         return shop;
      }

      BlockState state = level.getBlockState(pos);
      if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
         BlockPos partner = ChestBlock.getConnectedBlockPos(pos, state);
         if (partner != null) {
            return shops.get(keyFor(level, partner));
         }
      }

      return null;
   }

   public static boolean isShop(Level level, BlockPos pos) {
      return shops.containsKey(keyFor(level, pos));
   }

   /** True only for a shop that is actually trading. Used by the claim backstop
    *  and the chest-open path: a *closed* shop is deliberately just a chest
    *  again, so it must not inherit a trading shop's exemptions. */
   public static boolean isTradingShop(Level level, BlockPos pos) {
      ChestShop shop = get(level, pos);
      return shop != null && isTrading(shop);
   }

   public static void remove(String key) {
      shops.remove(key);
      EconomyManager.markDirty();
   }

   /** Closes a shop to visitors. The record stays, so prices, currency and stock
    *  survive being parked, and the chest keeps its protection. */
   public static boolean isTrading(ChestShop shop) {
      return shop != null && !TYPE_OFF.equals(shop.type);
   }

   /** buy -> sell -> off -> buy, the single place the cycle is defined so the
    *  GUI button, the sign click and the command can never disagree. */
   public static String nextType(String type) {
      if (TYPE_BUY.equals(type)) {
         return TYPE_SELL;
      }
      return TYPE_SELL.equals(type) ? TYPE_OFF : TYPE_BUY;
   }

   public static String typeName(String type) {
      return TYPE_OFF.equals(type) ? "closed" : type;
   }

   /** Deletes a shop outright, leaving the chest an ordinary chest again. */
   public static void delete(String key, MinecraftServer server) {
      remove(key);
      if (server != null) {
         save(server);
      }
   }

   public static boolean createShop(ServerPlayer player, BlockPos chestPos, String type) {
      Level level = player.level();
      if (level.isClientSide()) {
         return false;
      }

      BlockState state = level.getBlockState(chestPos);
      if (!(state.getBlock() instanceof ChestBlock)) {
         Chat.msg(player, "&cYou can only place shop signs on chests!");
         return false;
      }

      if (!ClaimManager.canBuild(player, chestPos)) {
         Chat.msg(player, "&cYou can't create a shop here - this land is claimed.");
         return false;
      }

      if (shops.containsKey(keyFor(level, chestPos))) {
         Chat.msg(player, "&cThis chest is already a shop.");
         return false;
      }

      ChestShop shop = new ChestShop();
      shop.type = type;
      shop.owner = player.getUUID();
      shops.put(keyFor(level, chestPos), shop);
      EconomyManager.markDirty();
      if (TYPE_BUY.equals(type)) {
         Chat.msg(player, "&aBuy shop created! Items in this chest are now for sale.");
      } else {
         Chat.msg(player, "&aSell shop created! Players can sell items here.");
      }

      Chat.msg(player, "&7Set prices: &f/chestshop price all <price> &7or &f/chestshop price <item> <price>");
      Chat.msg(player, "&7Set payment: &f/chestshop currency cash &7or &f/chestshop currency <item>");
      return true;
   }

   public static boolean payFrom(ServerPlayer payer, ChestShop shop, long amount) {
      if (amount <= 0L) {
         return false;
      }

      if (!shop.isItemCurrency()) {
         if (!EconomyManager.hasCash(payer.getUUID(), amount)) {
            Chat.msg(payer, "&cYou don't have enough money! You need " + Chat.moneyStr(amount));
            return false;
         } else {
            EconomyManager.takeCash(payer.getUUID(), amount);
            return true;
         }
      } else if (!shop.isTokenCurrency()) {
         Item item = shop.currencyItem();
         if (item != null && item != Items.AIR) {
            if (InventoryHelper.countItems(payer, item) < amount) {
               Chat.msg(payer, "&cYou need &e" + amount + "x &f" + new ItemStack(item).getHoverName().getString() + "&c!");
               return false;
            } else {
               InventoryHelper.removeItems(payer, item, (int)amount);
               return true;
            }
         } else {
            Chat.msg(payer, "&cInvalid payment item configured on this shop.");
            return false;
         }
      } else {
         if (InventoryHelper.countTokens(payer) < amount) {
            Chat.msg(payer, "&cYou need &e" + amount + "x &fToken&c!");
            return false;
         }

         for (ItemStack token : InventoryHelper.removeTokenStacks(payer, (int)amount)) {
            EconomyManager.giveItem(shop.owner, token);
         }

         return true;
      }
   }

   public static void payTo(ServerPlayer payer, ChestShop shop, long amount, UUID payee) {
      if (shop.isItemCurrency()) {
         if (shop.isTokenCurrency()) {
            return;
         }

         Item item = shop.currencyItem();
         if (item != null) {
            EconomyManager.giveItem(payee, new ItemStack(item, (int)amount));
         }
      } else {
         EconomyManager.addCash(payee, amount);
      }
   }

   public static boolean payOut(ServerPlayer recipient, ChestShop shop, long amount) {
      if (amount <= 0L) {
         return false;
      }

      if (!shop.isItemCurrency()) {
         EconomyManager.addCash(recipient.getUUID(), amount);
         return true;
      }

      if (!shop.isTokenCurrency()) {
         Item item = shop.currencyItem();
         if (item != null && item != Items.AIR) {
            InventoryHelper.giveOrDrop(recipient, new ItemStack(item, (int)amount));
            return true;
         } else {
            return false;
         }
      } else {
         for (ItemStack token : EconomyManager.removeTokensFromCollection(shop.owner, (int)amount)) {
            InventoryHelper.giveOrDrop(recipient, token);
         }

         return true;
      }
   }

   public static void onChestBroken(MinecraftServer server, Level level, BlockPos pos) {
      remove(keyFor(level, pos));
   }

   /** The key a shop is stored under, found from either half of a double chest. */
   public static String keyAt(Level level, BlockPos pos) {
      String key = keyFor(level, pos);
      if (shops.containsKey(key)) {
         return key;
      }
      BlockState state = level.getBlockState(pos);
      if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
         BlockPos partner = ChestBlock.getConnectedBlockPos(pos, state);
         if (partner != null && shops.containsKey(keyFor(level, partner))) {
            return keyFor(level, partner);
         }
      }
      return null;
   }

   public static Map<String, ChestShop> allShops() {
      return shops;
   }

   /** Saves at most once every {@code minGapMs}, so a busy shop trading many
    *  times a second does not turn every purchase into a file write. */
   public static void saveThrottled(MinecraftServer server, long minGapMs) {
      if (server == null) {
         return;
      }
      long now = System.currentTimeMillis();
      if (now - lastSaveAt < minGapMs) {
         return;
      }
      lastSaveAt = now;
      save(server);
   }

   // -------------------------------------------------------------- sales ledger

   /**
    * Records a completed trade against a shop, newest first. {@code kind} is
    * {@link #LEDGER_SOLD} when a customer bought stock out of a buy shop, or
    * {@link #LEDGER_BOUGHT} when a customer sold goods into a sell shop.
    *
    * <p>This is the owner-facing history behind {@code /chestshop ledger} and the
    * shop's Sales Ledger screen: what sold, how many, for how much and when.
    */
   public static void record(ChestShop shop, String kind, ItemStack stack, int qty, long total, String who) {
      if (shop == null || stack == null || stack.isEmpty() || qty <= 0) {
         return;
      }

      LedgerEntry entry = new LedgerEntry();
      entry.time = System.currentTimeMillis();
      entry.kind = kind;
      Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
      entry.itemId = id == null ? "minecraft:paper" : id.toString();
      entry.name = stack.getHoverName().getString();
      entry.qty = qty;
      entry.total = Math.max(0L, total);
      entry.who = who == null || who.isBlank() ? "someone" : who;
      shop.ledger.add(0, entry);

      while (shop.ledger.size() > LEDGER_LIMIT) {
         shop.ledger.remove(shop.ledger.size() - 1);
      }
   }

   /** The ledger lines, newest first, capped at {@code limit}. */
   public static List<LedgerEntry> recent(ChestShop shop, int limit) {
      if (shop == null) {
         return List.of();
      }
      return shop.ledger.subList(0, Math.min(Math.max(0, limit), shop.ledger.size()));
   }

   /** Per-item totals across the whole ledger, biggest quantity first. */
   public static List<ItemTotal> topItems(ChestShop shop, int limit) {
      List<ItemTotal> out = new ArrayList<>();
      if (shop == null) {
         return out;
      }

      Map<String, long[]> grouped = new HashMap<>();
      Map<String, String> names = new HashMap<>();

      for (LedgerEntry entry : shop.ledger) {
         String key = entry.itemId == null ? "minecraft:paper" : entry.itemId;
         long[] totals = grouped.computeIfAbsent(key, k -> new long[2]);
         totals[0] += entry.qty;
         totals[1] += entry.total;
         names.putIfAbsent(key, entry.name);
      }

      for (Entry<String, long[]> e : grouped.entrySet()) {
         out.add(new ItemTotal(e.getKey(), names.getOrDefault(e.getKey(), "?"), (int)Math.min(2147483647L, e.getValue()[0]), e.getValue()[1]));
      }

      out.sort(Comparator.comparingLong(ItemTotal::qty).reversed());
      return out.size() > limit ? out.subList(0, limit) : out;
   }

   /** Total value moved through the ledger (all of it, not just the recent page). */
   public static long ledgerTotal(ChestShop shop) {
      return ledgerTotal(shop, null);
   }

   /**
    * Ledger total for one direction, or every direction when {@code kind} is null.
    *
    * <p>The two directions are only directly comparable in a cash shop: a buy
    * shop's figure is the currency its customers paid, while a sell shop's figure
    * is the value of the goods it bought in. Item-currency shops read the two
    * separately rather than adding unlike units together.
    */
   public static long ledgerTotal(ChestShop shop, String kind) {
      if (shop == null) {
         return 0L;
      }
      long total = 0L;
      for (LedgerEntry entry : shop.ledger) {
         if (kind == null || kind.equals(entry.kind)) {
            total += entry.total;
         }
      }
      return total;
   }

   /** "just now", "4m ago", "2h ago", "3d ago" - short enough for a tooltip. */
   public static String ago(long time) {
      long seconds = Math.max(0L, (System.currentTimeMillis() - time) / 1000L);
      if (seconds < 45L) {
         return "just now";
      }
      if (seconds < 3600L) {
         return (seconds / 60L) + "m ago";
      }
      if (seconds < 86400L) {
         return (seconds / 3600L) + "h ago";
      }
      return (seconds / 86400L) + "d ago";
   }

   /** Resolves a ledger line's icon; falls back to paper if the item is gone. */
   public static ItemStack ledgerIcon(LedgerEntry entry) {
      return ledgerIconRaw(entry == null ? null : entry.itemId);
   }

   /** Resolves an item id to a display stack; falls back to paper when unknown. */
   public static ItemStack ledgerIconRaw(String itemId) {
      Identifier id = itemId == null ? null : Identifier.tryParse(itemId);
      Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
      return new ItemStack(item == null || item == Items.AIR ? Items.PAPER : item);
   }

   // ----------------------------------------------------- out-of-stock alerts

   /**
    * Warns a buy-shop owner that a sale just emptied one of their lines. Silently
    * does nothing when the owner is offline, has turned alerts off, or was already
    * told about this item moments ago - so one hot item cannot spam them.
    *
    * @param shop       the shop that just sold out
    * @param itemName   the line's display name, for the message
    * @param itemId     registry id, used to key the cooldown
    * @param server     the server, to find the owner
    */
   public static void alertOutOfStock(ChestShop shop, String itemName, String itemId, MinecraftServer server) {
      if (shop == null || server == null || !shop.restockAlerts || shop.owner == null) {
         return;
      }

      ServerPlayer owner = server.getPlayerList().getPlayer(shop.owner);
      if (owner == null) {
         return;
      }

      long now = System.currentTimeMillis();
      String key = shop.owner + "|" + (itemId == null ? itemName : itemId).toLowerCase(Locale.ROOT);
      Long last = lastAlertAt.get(key);
      if (last != null && now - last < ALERT_COOLDOWN_MS) {
         return;
      }

      lastAlertAt.put(key, now);
      Chat.msg(owner, "&c&lOut of stock! &7Your chest shop just sold its last &f" + itemName + "&7.");
      Chat.msg(owner, "&8Restock the chest to keep selling. Toggle this with &f/chestshop alerts&8.");
   }

   /** Registry id for an item stack, the ledger's/alert's stable key. */
   public static String itemIdOf(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return "minecraft:paper";
      }
      Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
      return id == null ? "minecraft:paper" : id.toString();
   }

   /** One recorded trade, from the owner's point of view. */
   public static final class LedgerEntry {
      public long time;
      public String kind = LEDGER_SOLD;
      public String itemId = "minecraft:paper";
      public String name = "?";
      public int qty;
      public long total;
      public String who = "someone";

      public JsonObject toJson() {
         JsonObject obj = new JsonObject();
         obj.addProperty("time", this.time);
         obj.addProperty("kind", this.kind);
         obj.addProperty("item", this.itemId);
         obj.addProperty("name", this.name);
         obj.addProperty("qty", this.qty);
         obj.addProperty("total", this.total);
         obj.addProperty("who", this.who);
         return obj;
      }

      public static LedgerEntry fromJson(JsonObject obj) {
         LedgerEntry entry = new LedgerEntry();
         entry.time = JsonUtil.jsonLong(obj, "time", 0L);
         entry.kind = JsonUtil.jsonString(obj, "kind", LEDGER_SOLD);
         entry.itemId = JsonUtil.jsonString(obj, "item", "minecraft:paper");
         entry.name = JsonUtil.jsonString(obj, "name", "?");
         entry.qty = JsonUtil.jsonInt(obj, "qty", 0);
         entry.total = JsonUtil.jsonLong(obj, "total", 0L);
         entry.who = JsonUtil.jsonString(obj, "who", "someone");
         return entry;
      }
   }

   /** Aggregated per-item ledger totals. */
   public record ItemTotal(String itemId, String name, int qty, long total) {
   }


    static public class ChestShop {
       public String type;
       public UUID owner;
       public String currency = "cash";
       public Map<String, Long> prices = new HashMap<>();
       /** Whether this shop's owner wants out-of-stock alerts in chat. */
       public boolean restockAlerts = true;
       /** Completed trades, newest first. */
       public List<LedgerEntry> ledger = new ArrayList<>();
    
       public long priceFor(Item item) {
          Identifier id = BuiltInRegistries.ITEM.getKey(item);
          long base = id == null ? BlockValues.buyPrice(item) : this.prices.getOrDefault(id.toString(), this.prices.getOrDefault("*", BlockValues.buyPrice(item)));
          if (this.isItemCurrency()) {
             long unit = this.isTokenCurrency() ? 1000L : this.currencyUnitValue();
             if (unit > 1L) {
                return Math.max(1L, base / unit);
             }
          }
    
          return base;
       }
    
       private long currencyUnitValue() {
          Item cur = this.currencyItem();
          return cur != null && cur != Items.AIR ? BlockValues.valueOf(cur) : 0L;
       }
    
       public boolean isItemCurrency() {
          return !"cash".equals(this.currency);
       }
    
       public boolean isTokenCurrency() {
          return "fortuneandfavors:token".equals(this.currency);
       }
    
       public Item currencyItem() {
          return this.isTokenCurrency() ? Items.PAPER : (Item)BuiltInRegistries.ITEM.getValue(Identifier.parse(this.currency));
       }
    }
}
