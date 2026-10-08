package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.AuctionManager.Auction;
import com.fortuneandfavors.economy.AuctionManager.Escrow;
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
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import java.util.Set;

public final class AuctionManager {
   public static final String CURRENCY_CASH = "cash";
   public static final String CURRENCY_TOKEN = "fortuneandfavors:token";
   private static final Map<Integer, Auction> auctions = new HashMap<>();
   private static int nextId = 1;
   private static Path dataFile;
   private static long currentTick = 0L;

   /** Anti-snipe: a bid landing inside this window (30s) extends the deadline by
    *  one window, capped at {@link #MAX_SNIPE_EXTENSIONS}, so the underbidder
    *  always gets a chance to respond. */
   public static final long SNIPE_WINDOW_TICKS = 600L;
   public static final int MAX_SNIPE_EXTENSIONS = 5;

   private AuctionManager() {
   }

   public static void load(MinecraftServer server) {
      auctions.clear();
      Provider access = server.registryAccess();
      dataFile = EconomyManager.getDataDir(server).resolve("auctions.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      nextId = (int)JsonUtil.jsonLong(root, "next_id", 1L);
      if (root.has("auctions") && root.get("auctions").isJsonArray()) {
         for (JsonElement el : root.getAsJsonArray("auctions")) {
            try {
               JsonObject obj = el.getAsJsonObject();
               Auction a = new Auction();
               a.id = obj.get("id").getAsInt();
               a.seller = UUID.fromString(obj.get("seller").getAsString());
               a.sellerName = JsonUtil.jsonString(obj, "seller_name", "");
               a.item = JsonUtil.jsonToItem(obj.get("item"), access);
               a.mode = JsonUtil.jsonString(obj, "mode", "bid");
               a.currency = JsonUtil.jsonString(obj, "currency", "cash");
               a.price = JsonUtil.jsonLong(obj, "price", 0L);
               a.minIncrement = JsonUtil.jsonLong(obj, "min_increment", 1L);
               a.endsAtTick = JsonUtil.jsonLong(obj, "ends_at_tick", 0L);
               a.finished = JsonUtil.jsonLong(obj, "finished", 0L) == 1L;
               a.currentBid = JsonUtil.jsonLong(obj, "current_bid", 0L);
               a.snipeExtensions = (int)JsonUtil.jsonLong(obj, "snipe_extensions", 0L);
               if (obj.has("top_bidder")) {
                  a.topBidder = UUID.fromString(obj.get("top_bidder").getAsString());
                  a.topBidderName = JsonUtil.jsonString(obj, "top_bidder_name", "");
               }

               if (obj.has("escrow") && obj.get("escrow").isJsonArray()) {
                  for (JsonElement eEl : obj.getAsJsonArray("escrow")) {
                     JsonObject eo = eEl.getAsJsonObject();
                     Escrow esc = new Escrow();
                     esc.bidder = UUID.fromString(eo.get("bidder").getAsString());
                     esc.bidderName = JsonUtil.jsonString(eo, "bidder_name", "");
                     esc.cash = JsonUtil.jsonLong(eo, "cash", 0L);
                     if (eo.has("items") && eo.get("items").isJsonArray()) {
                        for (JsonElement iEl : eo.getAsJsonArray("items")) {
                           ItemStack s = JsonUtil.jsonToItem(iEl, access);
                           if (!s.isEmpty()) {
                              esc.items.add(s);
                           }
                        }
                     }

                     a.escrow.put(esc.bidder, esc);
                  }
               }

               auctions.put(a.id, a);
            } catch (Exception var14) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("auctions.json");
      }

      Provider access = server.registryAccess();
      JsonObject root = new JsonObject();
      root.addProperty("next_id", nextId);
      JsonArray arr = new JsonArray();

      for (Auction a : auctions.values()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("id", a.id);
         obj.addProperty("seller", a.seller.toString());
         obj.addProperty("seller_name", a.sellerName);
         obj.add("item", JsonUtil.itemToJson(a.item, access));
         obj.addProperty("mode", a.mode);
         obj.addProperty("currency", a.currency);
         obj.addProperty("price", a.price);
         obj.addProperty("min_increment", a.minIncrement);
         obj.addProperty("ends_at_tick", a.endsAtTick);
         obj.addProperty("finished", a.finished ? 1 : 0);
         obj.addProperty("current_bid", a.currentBid);
         obj.addProperty("snipe_extensions", a.snipeExtensions);
         if (a.topBidder != null) {
            obj.addProperty("top_bidder", a.topBidder.toString());
            obj.addProperty("top_bidder_name", a.topBidderName);
         }

         JsonArray escArr = new JsonArray();

         for (Escrow esc : a.escrow.values()) {
            JsonObject eo = new JsonObject();
            eo.addProperty("bidder", esc.bidder.toString());
            eo.addProperty("bidder_name", esc.bidderName);
            eo.addProperty("cash", esc.cash);
            JsonArray items = new JsonArray();

            for (ItemStack s : esc.items) {
               items.add(JsonUtil.itemToJson(s, access));
            }

            eo.add("items", items);
            escArr.add(eo);
         }

         obj.add("escrow", escArr);
         arr.add(obj);
      }

      root.add("auctions", arr);
      JsonUtil.write(dataFile, root);
   }

   public static List<Auction> activeAuctions() {
      List<Auction> list = new ArrayList<>();

      for (Auction a : auctions.values()) {
         if (!a.finished) {
            list.add(a);
         }
      }

      return list;
   }

   public static Auction get(int id) {
      return auctions.get(id);
   }

   public static int createAuction(
      UUID seller, String sellerName, ItemStack item, String mode, long price, long minIncrement, long durationTicks, String currency
   ) {
      Auction a = new Auction();
      a.id = nextId++;
      a.seller = seller;
      a.sellerName = sellerName;
      a.item = item.copy();
      a.mode = mode;
      a.price = price;
      a.minIncrement = Math.max(1L, minIncrement);
      a.currency = currency;
      a.endsAtTick = durationTicks > 0L ? currentTick + durationTicks : Long.MAX_VALUE;
      auctions.put(a.id, a);
      return a.id;
   }

   public static void tick(MinecraftServer server) {
      currentTick = server.getTickCount();
      boolean changed = false;

      for (Auction a : new ArrayList<>(auctions.values())) {
         if (!a.finished && server.getTickCount() >= a.endsAtTick) {
            finishAuction(server, a);
            changed = true;
         }
      }

      if (changed) {
         save(server);
      }
   }

   private static void finishAuction(MinecraftServer server, Auction a) {
      a.finished = true;
      if (a.topBidder == null) {
         EconomyManager.giveItem(a.seller, a.item);
         notify(server, a.seller, "§7Your auction &f#" + a.id + "&7 ended with no bids - item returned.");
      } else {
         Escrow esc = (Escrow)a.escrow.get(a.topBidder);
         if (esc == null || esc.cash <= 0L && esc.items.isEmpty()) {
            EconomyManager.giveItem(a.seller, a.item);
            notify(server, a.seller, "§cAuction &f#" + a.id + "&7 ended without a valid payment - item returned.");

            for (Entry<UUID, Escrow> e : a.escrow.entrySet()) {
               refundEscrow(server, e.getValue());
            }

            a.escrow.clear();
         } else {
            if (esc.cash > 0L) {
               EconomyManager.addCash(a.seller, esc.cash);
            }

            for (ItemStack s : esc.items) {
               EconomyManager.giveItem(a.seller, s);
            }

            esc.cash = 0L;
            esc.items.clear();
            EconomyManager.giveItem(a.topBidder, a.item.copy());
            notify(server, a.seller, "§aYour auction &f#" + a.id + "&7 sold to §f" + a.topBidderName + "&7 for " + currencyString(a) + "!");
            notify(server, a.topBidder, "§aYou won auction &f#" + a.id + "&7! Claim it with &f/auction claim");
            com.fortuneandfavors.economy.ServerNewspaperManager.logEvent(
               server,
               "Auction #" + a.id + " closed: " + a.item.getHoverName().getString() + " went to " + a.topBidderName + " for " + currencyString(a) + "."
            );

            for (Entry<UUID, Escrow> e : a.escrow.entrySet()) {
               if (!e.getKey().equals(a.topBidder)) {
                  refundEscrow(server, e.getValue());
               }
            }

            a.escrow.clear();
         }
      }
   }

   private static void refundEscrow(MinecraftServer server, Escrow esc) {
      if (esc.cash > 0L) {
         EconomyManager.addCash(esc.bidder, esc.cash);
      }

      for (ItemStack s : esc.items) {
         EconomyManager.giveItem(esc.bidder, s);
      }

      esc.cash = 0L;
      esc.items.clear();
   }

   public static boolean cancelAuction(MinecraftServer server, ServerPlayer player, Auction a) {
      if (a.finished) {
         return false;
      }

      if (!a.seller.equals(player.getUUID())) {
         return false;
      }

      a.finished = true;

      for (Escrow esc : new ArrayList<>(a.escrow.values())) {
         refundEscrow(server, esc);
         notify(server, esc.bidder, "§cAuction &f#" + a.id + "&7 was cancelled - your bid was refunded.");
      }

      a.escrow.clear();
      EconomyManager.giveItem(a.seller, a.item);
      return true;
   }

   public static boolean bid(MinecraftServer server, ServerPlayer player, Auction a, long amount) {
      if (!"bid".equals(a.mode) || a.finished) {
         return false;
      }

      if (amount > 0L && amount <= 1000000000L) {
         if (a.seller.equals(player.getUUID())) {
            return false;
         }

         if (player.getUUID().equals(a.topBidder)) {
            return false;
         }

         long required = a.topBidder == null ? a.price : a.currentBid + a.minIncrement;
         if (amount < required) {
            return false;
         }

         Escrow esc = a.escrow.computeIfAbsent(player.getUUID(), k -> {
            Escrow e = new Escrow();
            e.bidder = player.getUUID();
            e.bidderName = player.getName().getString();
            return e;
         });
         if (a.isItemCurrency()) {
            if (a.isTokenCurrency()) {
               if (InventoryHelper.countTokens(player) < amount) {
                  return false;
               }

               for (ItemStack token : InventoryHelper.removeTokenStacks(player, (int)amount)) {
                  addStackToEscrow(esc, token);
               }
            } else {
               Item item = a.currencyItem();
               if (item == null || item == Items.AIR) {
                  return false;
               }

               long have = InventoryHelper.countItems(player, item);
               if (have < amount) {
                  return false;
               }

               InventoryHelper.removeItems(player, item, (int)amount);
               addStackToEscrow(esc, new ItemStack(item, (int)amount));
            }
         } else {
            if (!EconomyManager.hasCash(player.getUUID(), amount)) {
               return false;
            }

            EconomyManager.takeCash(player.getUUID(), amount);
            esc.cash += amount;
         }

         if (a.topBidder != null) {
            Escrow prev = (Escrow)a.escrow.get(a.topBidder);
            if (prev != null) {
               refundEscrow(server, prev);
               notify(server, a.topBidder, "§cYou've been outbid on auction &f#" + a.id + "&7! Your bid was refunded.");
            }

            a.escrow.remove(a.topBidder);
         }

         a.topBidder = player.getUUID();
         a.topBidderName = player.getName().getString();
         a.currentBid = amount;
         // Anti-snipe: a bid in the final 30 seconds reopens the countdown so
         // the previous bidder has time to counter. Capped so a war of snipes
         // can't stretch an auction forever.
         if (a.endsAtTick != Long.MAX_VALUE && a.snipeExtensions < MAX_SNIPE_EXTENSIONS
               && a.endsAtTick - currentTick <= SNIPE_WINDOW_TICKS) {
            a.endsAtTick += SNIPE_WINDOW_TICKS;
            a.snipeExtensions++;
            notify(
               server,
               player.getUUID(),
               "§e⚡ Late bid! The auction was extended by 30s (§f" + (MAX_SNIPE_EXTENSIONS - a.snipeExtensions) + "§e extension" + (MAX_SNIPE_EXTENSIONS - a.snipeExtensions == 1 ? "" : "s") + " left)."
            );
         }

         save(server);
         return true;
      } else {
         return false;
      }
   }

   public static boolean buyFixed(MinecraftServer server, ServerPlayer player, Auction a) {
      if (!ModConfig.buyNow()) {
         return false;
      }

      if ("fixed".equals(a.mode) && !a.finished) {
         if (a.seller.equals(player.getUUID())) {
            return false;
         }

         if (a.isItemCurrency()) {
            if (a.isTokenCurrency()) {
               if (InventoryHelper.countTokens(player) < a.price) {
                  return false;
               }

               for (ItemStack token : InventoryHelper.removeTokenStacks(player, (int)a.price)) {
                  giveStackInChunks(a.seller, token);
               }
            } else {
               Item item = a.currencyItem();
               if (item == null || item == Items.AIR) {
                  return false;
               }

               if (InventoryHelper.countItems(player, item) < a.price) {
                  return false;
               }

               InventoryHelper.removeItems(player, item, (int)a.price);
               giveStackInChunks(a.seller, new ItemStack(item, (int)a.price));
            }
         } else {
            if (!EconomyManager.hasCash(player.getUUID(), a.price)) {
               return false;
            }

            EconomyManager.takeCash(player.getUUID(), a.price);
            EconomyManager.addCash(a.seller, a.price);
         }

         a.finished = true;
         a.topBidder = player.getUUID();
         a.topBidderName = player.getName().getString();
         a.currentBid = a.price;
         EconomyManager.giveItem(player.getUUID(), a.item.copy());
         notify(
            server,
            a.seller,
            "§aYour fixed-price auction &f#" + a.id + "&7 was bought by §f" + player.getName().getString() + "&7 for " + currencyString(a) + "!"
         );
         save(server);
         return true;
      } else {
         return false;
      }
   }

   private static void addStackToEscrow(Escrow esc, ItemStack stack) {
      int max = stack.getMaxStackSize();
      int count = stack.getCount();

      while (count > 0) {
         int chunk = Math.min(max, count);
         esc.items.add(stack.copyWithCount(chunk));
         count -= chunk;
      }
   }

   private static void giveStackInChunks(UUID uuid, ItemStack stack) {
      int max = stack.getMaxStackSize();
      int count = stack.getCount();

      while (count > 0) {
         int chunk = Math.min(max, count);
         EconomyManager.giveItem(uuid, stack.copyWithCount(chunk));
         count -= chunk;
      }
   }

   private static void notify(MinecraftServer server, UUID uuid, String message) {
      ServerPlayer p = server.getPlayerList().getPlayer(uuid);
      if (p != null) {
         p.sendSystemMessage(Component.literal("§8[§dAuction§8]§7 " + message));
      }
   }

   public static String currencyString(Auction a) {
      long amount = a.currentBid > 0L ? a.currentBid : a.price;
      if (a.isItemCurrency()) {
         if (a.isTokenCurrency()) {
            return amount + "x Token";
         }

         Item item = a.currencyItem();
         return item == null ? "items" : amount + "x " + new ItemStack(item).getHoverName().getString();
      } else {
         return "§a$" + amount;
      }
   }

   public static long timeLeftTicks(Auction a) {
      return Math.max(0L, a.endsAtTick - currentTick);
   }

   public static String timeLeftString(Auction a) {
      long ticks = timeLeftTicks(a);
      long secs = ticks / 20L;
      if (secs >= 3600L) {
         return secs / 3600L + "h " + secs % 3600L / 60L + "m";
      } else {
         return secs >= 60L ? secs / 60L + "m " + secs % 60L + "s" : secs + "s";
      }
   }

   public static int count() {
      return activeAuctions().size();
   }

   public static List<Auction> ownAuctions(UUID uuid) {
      List<Auction> list = new ArrayList<>();

      for (Auction a : auctions.values()) {
         if (!a.finished && a.seller.equals(uuid)) {
            list.add(a);
         }
      }

      list.sort(Comparator.comparingInt(ax -> ax.id));
      return list;
   }

   public static int relistAuction(MinecraftServer server, ServerPlayer player, int oldId, long newPrice, long newMinIncrement, long durationTicks) {
      Auction old = auctions.get(oldId);
      if (old == null || old.finished || !old.seller.equals(player.getUUID())) {
         return -1;
      }

      if (!old.escrow.isEmpty()) {
         return -1;
      }

      if (newPrice <= 0L) {
         return -1;
      }

      ItemStack item = old.item;
      long duration = durationTicks > 0L ? durationTicks : Math.max(0L, old.endsAtTick - currentTick);
      String mode = old.mode;
      old.finished = true;
      int id = createAuction(player.getUUID(), player.getName().getString(), item, mode, newPrice, Math.max(1L, newMinIncrement), duration, old.currency);
      save(server);
      return id;
   }

   // ------------------------------------------------------------------
   // Browsing the market
   //
   // The auction house had one view of itself - every listing, newest first - which is fine for
   // twenty listings and useless for two hundred: a player looking for a stack of iron and a player
   // looking for a sword were reading the same page, and the only instrument either of them had was
   // paging. Everything below is the other half of that: what a listing is OF, and everything the
   // windows need to describe one without re-deriving it in three places.

   /** How close to the end a listing has to be before the list flags it. Five minutes. */
   public static final long ENDING_SOON_TICKS = 6_000L;

   /** The shelves a listing can be filed under. */
   public enum Category {
      ALL("§fEverything"),
      GEAR("§cTools & Gear"),
      BLOCKS("§7Blocks"),
      FOOD("§aFood & Farming"),
      MATERIALS("§bMaterials"),
      MACHINES("§6Machines & Mods"),
      OTHER("§8Misc");

      public final String label;

      Category(String label) {
         this.label = label;
      }

      /** The next shelf along, so one button can walk all seven. */
      public Category next() {
         Category[] all = values();
         return all[(this.ordinal() + 1) % all.length];
      }

      /** The previous shelf, so a right-click walks the button backwards. */
      public Category previous() {
         Category[] all = values();
         return all[(this.ordinal() + all.length - 1) % all.length];
      }
   }

   /** The common crafting materials, spelled out: a tag for "is this an ingot" does not exist. */
   private static final Set<Item> MATERIALS = Set.of(
      Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT, Items.NETHERITE_INGOT, Items.NETHERITE_SCRAP,
      Items.DIAMOND, Items.EMERALD, Items.LAPIS_LAZULI, Items.REDSTONE, Items.QUARTZ, Items.AMETHYST_SHARD,
      Items.CHARCOAL, Items.RAW_IRON, Items.RAW_GOLD, Items.RAW_COPPER, Items.ANCIENT_DEBRIS,
      Items.STRING, Items.LEATHER, Items.RABBIT_HIDE, Items.BONE, Items.FLINT, Items.GUNPOWDER, Items.SLIME_BALL,
      Items.BLAZE_ROD, Items.BLAZE_POWDER, Items.ENDER_PEARL, Items.ENDER_EYE, Items.GHAST_TEAR,
      Items.PHANTOM_MEMBRANE, Items.SHULKER_SHELL, Items.PRISMARINE_SHARD, Items.PRISMARINE_CRYSTALS,
      Items.NETHER_STAR, Items.HEART_OF_THE_SEA, Items.ECHO_SHARD, Items.GLOW_INK_SAC, Items.INK_SAC,
      Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.EXPERIENCE_BOTTLE, Items.GLOWSTONE_DUST, Items.SUGAR,
      Items.EGG, Items.HONEYCOMB, Items.NAUTILUS_SHELL, Items.TURTLE_SCUTE, Items.ARMADILLO_SCUTE
   );

   /** Farming bits that are neither food nor blocks worth filing as such. */
   private static final Set<Item> FARMING = Set.of(
      Items.WHEAT, Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS,
      Items.SUGAR_CANE, Items.BONE_MEAL, Items.COCOA_BEANS, Items.NETHER_WART, Items.SWEET_BERRIES
   );

   /**
    * Which shelf a listing belongs on.
    *
    * <p>Machines first, because a mod block is a block and "Blocks" is the least useful thing to
    * call something a player is shopping for by name; then food (the food component is the honest
    * test, so a modded meal files itself), then gear by tag, then the material list, then anything
    * that is a block, and misc for the rest.
    */
   public static Category categoryOf(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return Category.OTHER;
      }
      if (ModItems.isMachineItem(stack) || ModItems.typeOf(stack) != null) {
         return Category.MACHINES;
      }
      if (stack.get(net.minecraft.core.component.DataComponents.FOOD) != null || FARMING.contains(stack.getItem())) {
         return Category.FOOD;
      }
      if (stack.is(net.minecraft.tags.ItemTags.SWORDS)
         || stack.is(net.minecraft.tags.ItemTags.AXES)
         || stack.is(net.minecraft.tags.ItemTags.PICKAXES)
         || stack.is(net.minecraft.tags.ItemTags.SHOVELS)
         || stack.is(net.minecraft.tags.ItemTags.HOES)
         || stack.is(net.minecraft.tags.ItemTags.HEAD_ARMOR)
         || stack.is(net.minecraft.tags.ItemTags.CHEST_ARMOR)
         || stack.is(net.minecraft.tags.ItemTags.LEG_ARMOR)
         || stack.is(net.minecraft.tags.ItemTags.FOOT_ARMOR)
         || stack.is(Items.BOW) || stack.is(Items.CROSSBOW) || stack.is(Items.TRIDENT)
         || stack.is(Items.SHIELD) || stack.is(Items.ELYTRA) || stack.is(Items.FISHING_ROD)) {
         return Category.GEAR;
      }
      if (MATERIALS.contains(stack.getItem()) || stack.is(net.minecraft.tags.ItemTags.COALS)) {
         return Category.MATERIALS;
      }
      if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
         return Category.BLOCKS;
      }
      return Category.OTHER;
   }

   /** Everything on one shelf, cheapest-to-browse order left to the caller. Never null. */
   public static List<Auction> activeIn(Category category) {
      List<Auction> out = new ArrayList<>();
      for (Auction a : activeAuctions()) {
         if (category == null || category == Category.ALL || categoryOf(a.item) == category) {
            out.add(a);
         }
      }
      return out;
   }

   /**
    * Everything matching a search, case-insensitively, against the item's name, the seller's name
    * and the listing's own id.
    *
    * <p>Matching the id is what makes "the one somebody linked in chat as #42" findable, the seller
    * is what makes "anything from Ana" findable, and the item name is the case that actually gets
    * used. An empty query matches everything, so the text is never a filter that silently hides the
    * whole market.
    */
   public static List<Auction> search(String query) {
      List<Auction> out = new ArrayList<>();
      String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
      for (Auction a : activeAuctions()) {
         if (needle.isEmpty() || matches(a, needle)) {
            out.add(a);
         }
      }
      return out;
   }

   /** True when one listing answers to one lower-cased search string. */
   public static boolean matches(Auction a, String needle) {
      if (a == null || needle == null || needle.isEmpty()) {
         return true;
      }
      String q = needle.toLowerCase(java.util.Locale.ROOT);
      if (a.item.getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains(q)) {
         return true;
      }
      if (a.sellerName.toLowerCase(java.util.Locale.ROOT).contains(q)) {
         return true;
      }
      String id = "#" + a.id;
      return id.contains(q) || String.valueOf(a.id).equals(q);
   }

   /**
    * The listings this player has money in: winning one, or holding a bid on one.
    *
    * <p>This is the view the house was missing. A player who bids and walks away has no way at all
    * to see what they are committed to - which auctions are waiting on them, which they are winning,
    * and which have quietly been taken back off them - short of remembering the numbers.
    */
   public static List<Auction> auctionsBiddingOn(UUID uuid) {
      List<Auction> out = new ArrayList<>();
      if (uuid == null) {
         return out;
      }
      for (Auction a : activeAuctions()) {
         if (hasStake(a, uuid)) {
            out.add(a);
         }
      }
      out.sort(Comparator.comparingLong(AuctionManager::timeLeftTicks));
      return out;
   }

   /** True when this player has anything at risk in this auction: a bid, or the winning one. */
   public static boolean hasStake(Auction a, UUID uuid) {
      return a != null
         && uuid != null
         && (uuid.equals(a.topBidder) || a.escrow.containsKey(uuid));
   }

   /** True when this player is the one currently taking this auction home. */
   public static boolean isWinning(Auction a, UUID uuid) {
      return a != null && uuid != null && uuid.equals(a.topBidder);
   }

   /** How many players have money standing in this auction, including the one winning it. */
   public static int bidderCount(Auction a) {
      return a == null ? 0 : a.escrow.size();
   }

   /**
    * The least this auction will take right now.
    *
    * <p>The single place the number is worked out: the opening price while nobody has bid, and
    * afterwards the winning bid plus the seller's own increment. The window's bid buttons, the
    * custom-bid prompt and the plain {@code /auction bid} all ask this, so "what does it cost" has
    * one answer rather than three that can disagree.
    */
   public static long minimumBid(Auction a) {
      if (a == null) {
         return 0L;
      }
      return a.topBidder == null ? a.price : a.currentBid + Math.max(1L, a.minIncrement);
   }

   /** What a fixed-price listing costs, and what a bid listing wants to be started at. */
   public static long askingPrice(Auction a) {
      if (a == null) {
         return 0L;
      }
      return "fixed".equals(a.mode) ? a.price : minimumBid(a);
   }

   /**
    * True for a listing that is about to close - the one thing worth a colour in a list of 36.
    *
    * <p>The window is five minutes. It used to be fifty, which meant a half-hour listing spent a
    * sixth of its life tagged as ending and the tag stopped meaning anything; a listing's own length
    * is not stored, so the window has to be short enough to be true for every length the window can
    * hand out (the shortest is five minutes, and that one is flagged for its whole run).
    */
   public static boolean isEndingSoon(Auction a) {
      return a != null && a.endsAtTick != Long.MAX_VALUE && timeLeftTicks(a) <= ENDING_SOON_TICKS;
   }

   /** True for a listing that never ends until it is bought (a fixed-price shop stall). */
   public static boolean isOpenEnded(Auction a) {
      return a != null && a.endsAtTick == Long.MAX_VALUE;
   }

   /**
    * A ten-cell bar for one listing's remaining time, coloured by how much is left.
    *
    * <p>The clock is drawn against an hour rather than against the auction's own length, because
    * the length is not stored and an hour is the longest a listing can be given from the window. So
    * a fresh hour-long listing is a full bar and the last minute is one red cell, which is exactly
    * the reading a player wants from a list they are skimming: which of these is about to go.
    */
   public static String timeBar(Auction a) {
      if (a == null) {
         return "§8░░░░░░░░░░";
      }
      if (isOpenEnded(a)) {
         return "§7∞ §8no timer";
      }
      long secs = timeLeftTicks(a) / 20L;
      int cells = (int)Math.max(1L, Math.min(10L, (secs + 359L) / 360L));
      String colour = secs <= 60L ? "§c" : secs <= 600L ? "§e" : "§a";
      StringBuilder bar = new StringBuilder(colour);
      for (int i = 0; i < 10; i++) {
         bar.append(i < cells ? '█' : '░');
      }
      return bar.toString();
   }

   /**
    * The one-line status of a listing, from one player's point of view.
    *
    * <p>The list is read by three different people at once - the seller, the player winning it, and
    * everybody else - and the same row has to say something different to each of them. "Yours",
    * "you are winning", "you have been outbid" and a bare price are four readings of one line, and
    * a market that does not make them is a market where a player cannot see their own stake.
    */
   public static String statusLine(Auction a, UUID viewer) {
      if (a == null) {
         return "";
      }
      if (viewer != null && a.seller.equals(viewer)) {
         return a.topBidder == null
            ? "§6§lYOURS §7- no bids yet"
            : "§6§lYOURS §7- §f" + a.topBidderName + " §7leads at §f" + currencyString(a);
      }
      if (isWinning(a, viewer)) {
         return "§a§lYOU ARE WINNING";
      }
      if (viewer != null && a.escrow.containsKey(viewer)) {
         return "§c§lOUTBID";
      }
      return "§7Open to bids";
   }

   public static boolean isUnauctionable(ItemStack stack) {
      return stack.isEmpty()
         ? true
         : ModItems.isChunkClaimer(stack)
            || ModItems.isBuySign(stack)
            || ModItems.isSellSign(stack)
            || ModItems.isMachineItem(stack)
            || ModItems.isToken(stack);
   }


    static public class Auction {
       public int id;
       public UUID seller;
       public String sellerName = "";
       public ItemStack item = ItemStack.EMPTY;
       public String mode = "bid";
       public String currency = "cash";
       public long price = 0L;
       public long minIncrement = 1L;
       public long endsAtTick = 0L;
       public boolean finished = false;
       public long currentBid = 0L;
       public int snipeExtensions = 0;
       public UUID topBidder;
       public String topBidderName = "";
       public Map<UUID, Escrow> escrow = new HashMap<>();
    
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

    static public class Escrow {
       public UUID bidder;
       public String bidderName = "";
       public long cash = 0L;
       public List<ItemStack> items = new ArrayList<>();
    }
}
