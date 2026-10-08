package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.fortuneandfavors.ModSounds;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * The Item Sorter: a hopper that has opinions.
 *
 * <p>A vanilla hopper moves everything it can reach into whatever it faces, and the usual way to
 * make it discriminate is a corridor of comparators and locked hoppers that takes an afternoon to
 * build and one misclick to break. This replaces the whole contraption with one block and a filter
 * list: it pulls matching items out of the hoppers around it (and, if you tell it to, out of chests
 * and barrels as well) and passes them on; everything else it leaves exactly where it was.
 *
 * <p>It is deliberately inert until it has been given a filter. A machine that decides on its own
 * to start moving a player's items the moment it is placed is a machine that empties the wrong
 * chest once - so an unconfigured sorter accepts nothing at all, and says so when it is right
 * clicked.
 */
public final class ItemSorter {
   /** Pull from hoppers only - the default, and the one that cannot surprise anybody. */
   public static final String MODE_HOPPERS = "hoppers";
   /** Pull from any container at all: chests, barrels, machines. */
   public static final String MODE_CONTAINERS = "containers";
   /** Both. */
   public static final String MODE_BOTH = "both";

   /** Output: whatever the hopper's face points at, then up, then around - the vanilla answer. */
   public static final String OUT_AUTO = "auto";
   /** Output: the nearest container in reach, so a sorter can feed the barrel behind it. */
   public static final String OUT_NEARBY = "nearby";
   /** Output: one container the player picked, remembered by position. */
   public static final String OUT_PINNED = "pinned";
   /** How far the nearby search and the pin list look: blocks sideways, and up/down. */
   public static final int NEARBY_SIDE = 3;
   public static final int NEARBY_UP = 2;
   public static final int NEARBY_DOWN = 2;

   /** How many item types one sorter can be told to keep. */
   public static final int FILTER_MAX = 18;
   /** How many items move per 8-tick pulse, per source. */
   private static final int PULL_PER_PULSE = 8;

   private static final Map<String, Entry> sorters = new LinkedHashMap<>();
   private static Path dataFile;
   private static boolean loadHealthy = true;

   private ItemSorter() {
   }

   /**
    * One sorter's settings: what it keeps, where it looks for it, and where each thing goes.
    *
    * <p>The list is a map and not a set because the two questions a player asks of a sorter - "will
    * you keep this?" and "where will you put it?" - are the same list answered twice. An entry with
    * no destination follows the sorter's own output setting; an entry with one goes to that container
    * and nowhere else, so a base can keep one hopper line feeding several barrels without a second
    * machine in the chain.
    */
   public static final class Entry {
      String mode = MODE_HOPPERS;
      String out = OUT_AUTO;
      /** The pinned default output, as "x,y,z". Null means nothing is pinned. */
      String target;
      /** The list in the order it was built up: item id to the container it is bound to, or null. */
      final Map<String, String> items = new LinkedHashMap<>();
      /** The tags of the marked containers this sorter pulls from remotely, in the order chosen. */
      final List<String> links = new ArrayList<>();
      /**
       * The tag a Transfer Hopper hands everything to, if it has one.
       *
       * <p>Input links are a list because a base has many sources; the output is a single promise,
       * because "send it all there" means one place. A sorter never sets this - it is the transfer
       * hopper's one extra verb.
       */
      String outTag;

      public String mode() {
         return this.mode;
      }

      /** Where unbound items go: {@link #OUT_AUTO}, {@link #OUT_NEARBY} or {@link #OUT_PINNED}. */
      public String outMode() {
         return this.out;
      }

      /** The pinned default output position as "x,y,z", or null. Only meaningful when pinned. */
      public String target() {
         return this.target;
      }

      /**
       * The item ids in the list, in the order they were added. Live: removing from it takes that
       * item out, which is what the window's "empty the list" does.
       */
      public Set<String> items() {
         return this.items.keySet();
      }

      public int size() {
         return this.items.size();
      }

      public boolean contains(String id) {
         return this.items.containsKey(id);
      }

      /** The container one item is bound to, as "x,y,z", or null when it follows the default. */
      public String destination(String id) {
         return this.items.get(id);
      }

      /** Puts one item in the list with no destination - it follows the sorter's own output. */
      public boolean add(String id) {
         if (id == null || id.isBlank() || this.items.containsKey(id)) {
            return false;
         }
         this.items.put(id, null);
         return true;
      }

      public boolean remove(String id) {
         return this.items.remove(id) != null;
      }

      public void clear() {
         this.items.clear();
      }

      /** Points one entry that is already in the list at one container. */
      void bind(String id, String destination) {
         if (this.items.containsKey(id)) {
            this.items.put(id, destination);
         }
      }

      /** The tags this sorter reaches for, in order. Live, like the list. */
      public List<String> links() {
         return this.links;
      }

      /** The tag this transfer hopper hands everything to, or null when it has none. */
      public String outTag() {
         return this.outTag;
      }

      void setOutTag(String tag) {
         this.outTag = tag == null || tag.isBlank() ? null : tag;
      }

      public boolean isLinked(String tag) {
         return this.links.contains(tag);
      }

      /** True when this stack is one the sorter was told to keep. An empty list keeps nothing. */
      public boolean accepts(ItemStack stack) {
         return stack != null && !stack.isEmpty() && this.items.containsKey(idOf(stack));
      }
   }

   public static void load(MinecraftServer server) {
      sorters.clear();
      loadHealthy = true;
      dataFile = EconomyManager.getDataDir(server).resolve("sorters.json");
      if (!Files.exists(dataFile)) {
         return;
      }
      try {
         String content = Files.readString(dataFile);
         if (content.isBlank()) {
            return;
         }
         JsonObject root = (JsonObject)JsonUtil.gson().fromJson(content, JsonObject.class);
         if (root == null || !root.has("sorters") || !root.get("sorters").isJsonObject()) {
            return;
         }
         for (java.util.Map.Entry<String, JsonElement> e : root.getAsJsonObject("sorters").entrySet()) {
            JsonElement el = e.getValue();
            if (el == null || !el.isJsonObject()) {
               continue;
            }            JsonObject obj = el.getAsJsonObject();
            Entry entry = new Entry();
            String mode = JsonUtil.jsonString(obj, "mode", MODE_HOPPERS);
            entry.mode = MODE_CONTAINERS.equals(mode) || MODE_BOTH.equals(mode) ? mode : MODE_HOPPERS;
            String out = JsonUtil.jsonString(obj, "out", OUT_AUTO);
            entry.out = OUT_NEARBY.equals(out) || OUT_PINNED.equals(out) ? out : OUT_AUTO;
            String target = JsonUtil.jsonString(obj, "target", "");
            entry.target = parsePos(target) == null ? null : target;
            entry.setOutTag(JsonUtil.jsonString(obj, "outTag", ""));
            if (obj.has("links") && obj.get("links").isJsonArray()) {
               for (JsonElement tag : obj.getAsJsonArray("links")) {
                  String id = tag.getAsString();
                  if (!id.isBlank() && entry.links.size() < SorterLinks.MAX_LINKS && !entry.links.contains(id)) {
                     entry.links.add(id);
                  }
               }
            }
            if (obj.has("items")) {
               // Two shapes are read and one is written: files from before per-item destinations are
               // a list of ids, and files from after are an id to the container it is bound to (an
               // empty string meaning "follow the default output").
               JsonElement items = obj.get("items");
               if (items.isJsonArray()) {
                  for (JsonElement item : items.getAsJsonArray()) {
                     addTo(entry, item.getAsString(), null);
                  }
               } else if (items.isJsonObject()) {
                  for (java.util.Map.Entry<String, JsonElement> binding : items.getAsJsonObject().entrySet()) {
                     JsonElement destination = binding.getValue();
                     addTo(
                        entry,
                        binding.getKey(),
                        destination == null || destination.isJsonNull() ? null : destination.getAsString()
                     );
                  }
               }
            }
            sorters.put(e.getKey(), entry);
         }
      } catch (Exception e) {
         loadHealthy = false;
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.error("Item sorter load failed - writes are disabled to protect sorters.json", e);
      }
   }

   public static boolean save(MinecraftServer server) {
      if (!loadHealthy) {
         return false;
      }
      if (dataFile == null && server != null) {
         dataFile = EconomyManager.getDataDir(server).resolve("sorters.json");
      }
      if (dataFile == null) {
         return false;
      }
      JsonObject root = new JsonObject();
      JsonObject all = new JsonObject();
      for (java.util.Map.Entry<String, Entry> e : sorters.entrySet()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("mode", e.getValue().mode);
         obj.addProperty("out", e.getValue().out);
         if (e.getValue().target != null) {
            obj.addProperty("target", e.getValue().target);
         }
         if (e.getValue().outTag != null) {
            obj.addProperty("outTag", e.getValue().outTag);
         }
         JsonArray links = new JsonArray();
         for (String tag : e.getValue().links) {
            links.add(tag);
         }
         obj.add("links", links);
         JsonObject items = new JsonObject();
         for (java.util.Map.Entry<String, String> item : e.getValue().items.entrySet()) {
            String destination = item.getValue();
            items.addProperty(item.getKey(), parsePos(destination) == null ? "" : destination);
         }
         obj.add("items", items);
         all.add(e.getKey(), obj);
      }
      root.add("sorters", all);
      return JsonUtil.write(dataFile, root);
   }

   /** The settings for one sorter, created empty on first use. Never null. */
   public static Entry entry(Level level, BlockPos pos) {
      return sorters.computeIfAbsent(MachineManager.keyFor(level, pos), k -> new Entry());
   }

   /** Settings for a raw key, for the audit and the recovery paths. */
   public static Entry entryFor(String key) {
      return sorters.get(key);
   }

   /** One item off a saved file, honoured only while the list still has room. */
   private static void addTo(Entry entry, String id, String destination) {
      if (entry.size() >= FILTER_MAX || !entry.add(id)) {
         return;
      }
      if (parsePos(destination) != null) {
         entry.bind(id, destination);
      }
   }

   /**
    * Drops the settings held for one position.
    *
    * <p>A sorter's filter is keyed by where it stands, which is deliberate: pick a sorter up and put
    * it back in the same place and its list is waiting for it. A position that never has a sorter
    * again, though, should not keep its row for the life of the world - so anything that is finished
    * with one says so here rather than reaching into the map.
    */
   public static boolean forget(Level level, BlockPos pos) {
      if (level == null || pos == null) {
         return false;
      }
      boolean removed = sorters.remove(MachineManager.keyFor(level, pos)) != null;
      if (removed) {
         save(level.getServer());
      }
      return removed;
   }

   public static int count() {
      return sorters.size();
   }

   /**
    * True when any placed sorter anywhere keeps this item.
    *
    * <p>This is what the Item Checker Hopper asks to decide what is "junk": an item that at least
    * one sorter in the world names is an item somebody is sorting, and the checker leaves it alone.
    * It reads the live filter list, so it follows a sorter being reconfigured immediately.
    */
   public static boolean acceptsAnywhere(String itemId) {
      if (itemId == null || itemId.isBlank()) {
         return false;
      }
      for (Entry e : sorters.values()) {
         if (e.items.containsKey(itemId)) {
            return true;
         }
      }
      return false;
   }

   /** The settings for a Transfer Hopper: same store as a sorter, without a filter. */
   public static Entry transferEntry(Level level, BlockPos pos) {
      return entry(level, pos);
   }

   /** What the sorter says about itself, and what the window's header item prints into chat. */
   public static void describe(ServerPlayer player, Level level, BlockPos pos) {
      Entry e = entry(level, pos);
      if (e.size() == 0) {
         Chat.msg(player, "&3Item Sorter: &7nothing filtered yet - right-click it with an item to add that item.");
      } else {
         Chat.msg(player, "&3Item Sorter: &7keeping &f" + e.size() + "&7 item type(s) - " + sample(e));
         for (String id : e.items()) {
            String destination = e.destination(id);
            if (parsePos(destination) != null) {
               Chat.msg(player, "&7  &f" + shortName(id) + " &7-> &f" + destination);
            }
         }
      }
      Chat.msg(player, "&7Reading: &f" + modeName(e.mode) + "&7. Output: &f" + outputName(e) + "&7.");
      if (!e.links.isEmpty()) {
         Chat.msg(player, "&7Also pulling from &f" + String.join("§7, §f", e.links) + "&7.");
      }
      Chat.msg(player, "&7Right-click with an empty hand to open it and change any of that.");
   }

   private static String modeName(String mode) {
      return switch (mode) {
         case MODE_CONTAINERS -> "any container beside it";
         case MODE_BOTH -> "hoppers and other containers";
         default -> "hoppers only";
      };
   }

   /**
    * Where the sorter hands things on, in words.
    *
    * <p>Shared with the window so the block and its menu can never disagree about what the setting
    * is - the same reason {@link #modeName} is not duplicated.
    */
   public static String outputName(Entry e) {
      if (OUT_NEARBY.equals(e.out)) {
         return "the nearest container in reach";
      }
      if (OUT_PINNED.equals(e.out)) {
         BlockPos pinned = parsePos(e.target);
         return pinned == null
            ? "nothing - no container is pinned"
            : "the container at " + pinned.getX() + ", " + pinned.getY() + ", " + pinned.getZ();
      }
      return "the way it points, then up, then around";
   }

   private static String sample(Entry e) {
      List<String> names = new ArrayList<>();
      for (String id : e.items()) {
         if (names.size() >= 5) {
            names.add("...");
            break;
         }
         String destination = e.destination(id);
         names.add(parsePos(destination) == null ? shortName(id) : shortName(id) + " \u2192 " + destination);
      }
      return String.join("§7, §f", names);
   }

   private static String shortName(String id) {
      int colon = id.indexOf(58);
      String path = colon < 0 ? id : id.substring(colon + 1);
      return path.replace('_', ' ');
   }

   /** Adds or removes one item type from the filter. */
   public static void toggleItem(ServerPlayer player, Level level, BlockPos pos, ItemStack stack) {
      if (stack.isEmpty()) {
         return;
      }
      Entry e = entry(level, pos);
      String id = idOf(stack);
      if (e.contains(id)) {
         e.remove(id);
         Chat.msg(player, "&7Removed &f" + shortName(id) + "&7 from the sorter's filter (" + e.size() + " left).");
      } else if (e.size() >= FILTER_MAX) {
         Chat.msg(player, "&cThis sorter is holding the most it can (" + FILTER_MAX + " item types). Remove one first.");
         return;
      } else {
         e.add(id);
         Chat.msg(player, "&aAdded &f" + shortName(id) + "&a to the sorter's filter (" + e.size() + " now).");
      }
      save(player.level().getServer());
      SoundUtil.play(player, ModSounds.TRANSFER);
      if (level instanceof ServerLevel server) {
         server.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.05);
      }
   }

   /** Cycles what the sorter is allowed to read from. */
   public static void cycleMode(ServerPlayer player, Level level, BlockPos pos) {
      Entry e = entry(level, pos);
      e.mode = switch (e.mode) {
         case MODE_HOPPERS -> MODE_CONTAINERS;
         case MODE_CONTAINERS -> MODE_BOTH;
         default -> MODE_HOPPERS;
      };
      save(player.level().getServer());
      Chat.msg(player, "&3Item Sorter now reads &f" + modeName(e.mode) + "&3.");
      SoundUtil.play(player, ModSounds.TRANSFER);
   }

   /**
    * Cycles where the sorter hands its items on: the way it points, the nearest container, one
    * pinned container. Reaching the pin with nothing chosen takes the nearest one it can see, so the
    * setting is never a dead end.
    */
   public static void cycleOutput(ServerPlayer player, Level level, BlockPos pos) {
      Entry e = entry(level, pos);
      e.out = switch (e.out) {
         case OUT_AUTO -> OUT_NEARBY;
         case OUT_NEARBY -> OUT_PINNED;
         default -> OUT_AUTO;
      };
      if (OUT_PINNED.equals(e.out) && parsePos(e.target) == null) {
         List<BlockPos> candidates = outputCandidates(level, pos);
         if (!candidates.isEmpty()) {
            e.target = posKey(candidates.get(0));
         }
      }
      save(player.level().getServer());
      Chat.msg(player, "&3Item Sorter now hands items to &f" + outputName(e) + "&3.");
      if (OUT_PINNED.equals(e.out) && parsePos(e.target) == null) {
         Chat.msg(player, "&7Nothing is in reach to pin - put a chest, barrel or hopper within " + NEARBY_SIDE + " blocks.");
      }
      SoundUtil.play(player, ModSounds.TRANSFER);
   }

   /**
    * Steps the pinned output through the containers around the sorter, then off again.
    *
    * <p>The list is the containers the sorter can actually reach - nearest first, and never one it
    * reads from, which would be a loop rather than a destination. The last step clears the pin and
    * puts the output back on auto, so a player who has cycled past what they wanted can get back.
    */
   public static void cycleTarget(ServerPlayer player, Level level, BlockPos pos) {
      Entry e = entry(level, pos);
      List<BlockPos> candidates = outputCandidates(level, pos);
      if (candidates.isEmpty()) {
         Chat.msg(player, "&cNo container is within reach - the sorter can only hand items to a chest, barrel or hopper near it.");
         return;
      }
      String current = e.target;
      int index = -1;
      for (int i = 0; i < candidates.size(); i++) {
         if (posKey(candidates.get(i)).equals(current)) {
            index = i;
            break;
         }
      }
      if (index + 1 >= candidates.size()) {
         e.target = null;
         e.out = OUT_AUTO;
         save(player.level().getServer());
         Chat.msg(player, "&7Output unpinned - back to &f" + outputName(e) + "&7.");
         SoundUtil.play(player, ModSounds.TRANSFER);
         return;
      }
      BlockPos next = candidates.get(index + 1);
      e.target = posKey(next);
      e.out = OUT_PINNED;
      save(player.level().getServer());
      Chat.msg(
         player,
         "&aPinned the output to the " + containerName(level, next) + " at &f" + next.getX() + ", " + next.getY() + ", " + next.getZ()
            + " &a(&f" + (index + 2) + "&a/&f" + (candidates.size() + 1) + "&a)."
      );
      SoundUtil.play(player, ModSounds.TRANSFER);
   }

   /**
    * Points one item at one container: the piece that makes the output customizable per item.
    *
    * <p>The item stays in the list it was already in - this only decides where the sorter puts it
    * from now on, so "keep iron" and "send the iron to the barrel by the door" are one decision made
    * in one window instead of two machines and a length of hopper.
    */
   public static void bindItem(ServerPlayer player, Level level, BlockPos pos, String id, BlockPos destination) {
      Entry e = entry(level, pos);
      if (!e.contains(id)) {
         return;
      }
      if (destination == null || destination.equals(pos)
         || !isReachableContainer(level, destination, MachineManager.ownerAt(level, pos))) {
         Chat.msg(player, "&cThat is not a container this sorter can reach.");
         return;
      }
      e.bind(id, posKey(destination));
      save(player.level().getServer());
      Chat.msg(
         player,
         "&a" + shortName(id) + " &awill go to the " + containerName(level, destination) + " at &f"
            + destination.getX() + ", " + destination.getY() + ", " + destination.getZ() + "&a."
      );
      SoundUtil.play(player, ModSounds.TRANSFER);
   }

   /** Sends one item back to the sorter's own output setting. */
   public static void unbindItem(ServerPlayer player, Level level, BlockPos pos, String id) {
      Entry e = entry(level, pos);
      if (!e.contains(id) || parsePos(e.destination(id)) == null) {
         return;
      }
      e.bind(id, null);
      save(player.level().getServer());
      Chat.msg(player, "&7" + shortName(id) + " follows the sorter's own output again.");
      SoundUtil.play(player, ModSounds.TRANSFER);
   }

   /** Pins the sorter's own output - where everything unbound goes - to one container. */
   public static void pinDefault(ServerPlayer player, Level level, BlockPos pos, BlockPos destination) {
      Entry e = entry(level, pos);
      if (destination == null || destination.equals(pos)
         || !isReachableContainer(level, destination, MachineManager.ownerAt(level, pos))) {
         Chat.msg(player, "&cThat is not a container this sorter can reach.");
         return;
      }
      e.target = posKey(destination);
      e.out = OUT_PINNED;
      save(player.level().getServer());
      Chat.msg(
         player,
         "&aEverything without a destination of its own now goes to the " + containerName(level, destination) + " at &f"
            + destination.getX() + ", " + destination.getY() + ", " + destination.getZ() + "&a."
      );
      SoundUtil.play(player, ModSounds.TRANSFER);
   }

   /** Takes the sorter's own output back off a pin, so it points where the hopper points again. */
   public static void unpinDefault(ServerPlayer player, Level level, BlockPos pos) {
      Entry e = entry(level, pos);
      if (e.target == null && OUT_AUTO.equals(e.out)) {
         return;
      }
      e.target = null;
      e.out = OUT_AUTO;
      save(player.level().getServer());
      Chat.msg(player, "&7The sorter's own output is back to &f" + outputName(e) + "&7.");
      SoundUtil.play(player, ModSounds.TRANSFER);
   }

   /**
    * The containers a sorter could hand items to, nearest first.
    *
    * <p>This is the list behind the bottom row of the window and both of its controls: any container
    * block within {@link #NEARBY_SIDE} blocks sideways and {@link #NEARBY_UP}/{@link #NEARBY_DOWN} up
    * and down, minus the sorter itself and minus chest shops, whose contents belong to a shop and not
    * to a hopper. Claim rules are asked once per candidate here so the search cannot reach into land
    * the machine's owner may not touch.
    *
    * <p>A container the sorter reads from is on this list, deliberately. It is a perfectly good
    * destination when a player says so - "keep the iron from that chest and put the ingots back in
    * it" - and only the automatic routes refuse to hand things straight back to where they came
    * from. See defaultTargets.
    */
   public static List<BlockPos> outputCandidates(Level level, BlockPos pos) {
      List<BlockPos> found = new ArrayList<>();
      if (level == null || pos == null) {
         return found;
      }
      UUID owner = MachineManager.ownerAt(level, pos);
      for (int dx = -NEARBY_SIDE; dx <= NEARBY_SIDE; dx++) {
         for (int dy = -NEARBY_DOWN; dy <= NEARBY_UP; dy++) {
            for (int dz = -NEARBY_SIDE; dz <= NEARBY_SIDE; dz++) {
               if (dx == 0 && dy == 0 && dz == 0) {
                  continue;
               }
               BlockPos at = pos.offset(dx, dy, dz);
               if (!isReachableContainer(level, at, owner)) {
                  continue;
               }
               // A double chest is one container and gets one row: the canonical half stands for
               // the pair, so the window cannot offer the same chest twice.
               if (!canonicalPos(level, at).equals(at)) {
                  continue;
               }
               found.add(at);
            }
         }
      }
      found.sort((a, b) -> Double.compare(distance(a, pos), distance(b, pos)));
      return found;
   }

   private static double distance(BlockPos a, BlockPos b) {
      double dx = a.getX() - b.getX();
      double dy = a.getY() - b.getY();
      double dz = a.getZ() - b.getZ();
      return dx * dx + dy * dy + dz * dz;
   }

   private static boolean isReachableContainer(Level level, BlockPos at, UUID owner) {
      if (containerAt(level, at) == null) {
         return false;
      }
      return ChestShopManager.get(level, at) == null && ClaimManager.canInteract(owner, level, at);
   }

   /**
    * The container standing at one position, with both halves of a double chest folded into one.
    *
    * <p>A double chest is two block entities and one container, and a sorter that treated each half
    * on its own would read the same chest twice and only ever fill half of it. Vanilla already
    * answers "what is the container here": {@link ChestBlock#getContainer} hands back the single
    * chest entity for one chest and a compound of the pair for two - the same 54 slots a player sees
    * when they open it. The blocked flag is passed as {@code true} so a chest with a block sitting on
    * top is still a container from a hopper's point of view; a hopper does not need the lid to lift.
    *
    * <p>Everything that used to ask {@code getBlockEntity(at) instanceof Container} asks this
    * instead, which is the whole of the double-chest fix: read, push, tag and destination lists all
    * see one chest per double.
    */
   public static Container containerAt(Level level, BlockPos pos) {
      if (level == null || pos == null) {
         return null;
      }
      BlockState state = level.getBlockState(pos);
      if (state.getBlock() instanceof ChestBlock chest) {
         Container combined = ChestBlock.getContainer(chest, state, level, pos, true);
         if (combined != null) {
            return combined;
         }
      }
      return level.getBlockEntity(pos) instanceof Container c ? c : null;
   }

   /**
    * One position per container, so a double chest is spoken about once rather than twice.
    *
    * <p>Both halves answer with the same pair, so a list that walked every container beside a sorter
    * would offer the same chest twice - once per half - and both halves would be read on the same
    * pulse. The connected half is folded back to the smaller of the two positions, which is stable
    * whichever half a walk reached first.
    */
   private static BlockPos canonicalPos(Level level, BlockPos pos) {
      BlockPos partner = partnerOf(level, pos);
      if (partner != null && compare(partner, pos) < 0) {
         return partner;
      }
      return pos;
   }

   /**
    * True when two positions are the same container - including the two halves of one double chest.
    *
    * <p>"Do not read from the chest I send to" has to survive the chest being a double: the tag and
    * the tagged half are one place, and the other half is the same place again.
    */
   private static boolean isSameContainer(Level level, BlockPos a, BlockPos b) {
      if (a == null || b == null) {
         return false;
      }
      return a.equals(b) || canonicalPos(level, a).equals(canonicalPos(level, b));
   }

   /**
    * Where a Transfer Hopper's output tag stands right now, or {@code null} when there is nothing to
    * send to.
    *
    * <p>A tag is a name rather than a coordinate, so the place is looked up afresh on every pulse. A
    * name that now carries no container - it was moved, broken or taken out of reach - simply finds
    * nothing and the hopper falls back to following the way it faces, which is what a hopper with no
    * output tag does anyway.
    */
   public static BlockPos outTagTarget(Level level, BlockPos pos, Entry e, UUID owner) {
      if (level == null || pos == null || e == null || e.outTag == null) {
         return null;
      }
      SorterLinks.Link out = SorterLinks.find(e.outTag);
      if (out == null
         || !out.dimension().equals(level.dimension().identifier().toString())
         || !SorterLinks.inReach(pos, out.pos())
         || (owner != null && out.owner() != null && !out.owner().equals(owner))) {
         return null;
      }
      return out.pos();
   }

   /**
    * The container an Overflow Hopper is filling right now - the first stop on the route it would
    * take with its tag unset - or {@code null} when there is nothing there.
    *
    * <p>This is {@link #defaultTargets} asked for its first answer only, and asked without the source
    * list that route normally carries around with it. The source filter exists to stop a hopper
    * handing a stack straight back to the chest it just took it out of; the container this hopper is
    * <i>filling</i> is a destination and never a source, so there is nothing for the filter to
    * remove, and leaving it out keeps this readable. The output tag is skipped for the same reason it
    * is skipped there: it is the spillway, not the first stop.
    *
    * <p>It answers the question the block is named after - "is what I am filling full?" - which is
    * why it lives here rather than in the window: the window is told what to print, it does not go
    * looking for chests.
    */
   public static BlockPos fillingTarget(Level level, BlockPos pos) {
      if (level == null || pos == null) {
         return null;
      }
      Entry e = entry(level, pos);
      BlockPos outTagPos = outTagTarget(level, pos, e, MachineManager.ownerAt(level, pos));
      if (OUT_PINNED.equals(e.out)) {
         BlockPos pinned = parsePos(e.target);
         return pinned != null && containerAt(level, pinned) != null ? pinned : null;
      }
      if (OUT_NEARBY.equals(e.out)) {
         for (BlockPos at : outputCandidates(level, pos)) {
            if (!isSameContainer(level, at, outTagPos)) {
               return at;
            }
         }
         return null;
      }
      BlockState state = level.getBlockState(pos);
      Direction facing = state.getBlock() instanceof HopperBlock ? state.getValue(HopperBlock.FACING) : Direction.DOWN;
      List<BlockPos> route = new ArrayList<>();
      route.add(pos.relative(facing));
      route.add(pos.above());
      for (Direction d : Direction.Plane.HORIZONTAL) {
         route.add(pos.relative(d));
      }
      for (BlockPos at : route) {
         if (containerAt(level, at) != null && !isSameContainer(level, at, outTagPos)) {
            return at;
         }
      }
      return null;
   }

   /**
    * True when the chest an Overflow Hopper fills has no room left - the question the block is named
    * after, and the one its window answers in a word.
    *
    * <p>Full means full: not one empty slot and not one stack that could take a single further item.
    * A chest of unstackable things is therefore full when every slot is taken, which is what a player
    * means and what the hopper in front of it means too.
    *
    * <p>The machine itself does not consult this. It moves items by trying to insert them and
    * spilling over whatever a destination refuses, which is strictly more accurate than any slot
    * count - a chest that is not full can still have nowhere to put <i>this</i> stack. This is the
    * readable answer for a person looking at the window, and the window says so.
    */
   public static boolean fillingChestFull(Level level, BlockPos pos) {
      BlockPos filling = fillingTarget(level, pos);
      return filling != null && isFull(containerAt(level, filling));
   }

   /** True when a container could take nothing at all: no empty slot, no stack with room in it. */
   public static boolean isFull(Container container) {
      if (container == null) {
         return false;
      }
      for (int i = 0; i < container.getContainerSize(); i++) {
         ItemStack s = container.getItem(i);
         if (s.isEmpty() || s.getCount() < s.getMaxStackSize()) {
            return false;
         }
      }
      return true;
   }

   /** The other half of a double chest at {@code pos}, or {@code null} when there is not one. */
   private static BlockPos partnerOf(Level level, BlockPos pos) {
      BlockState state = level.getBlockState(pos);
      if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
         return pos.relative(ChestBlock.getConnectedDirection(state));
      }
      return null;
   }

   /** A stable order for two positions, so a double chest's canonical half is the same everywhere. */
   private static int compare(BlockPos a, BlockPos b) {
      if (a.getX() != b.getX()) {
         return Integer.compare(a.getX(), b.getX());
      }
      if (a.getY() != b.getY()) {
         return Integer.compare(a.getY(), b.getY());
      }
      return Integer.compare(a.getZ(), b.getZ());
   }

   /** The vanilla block a candidate container is, for a sentence a player can picture. */
   private static String containerName(Level level, BlockPos at) {
      try {
         return BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).getPath().replace('_', ' ');
      } catch (Throwable ignored) {
         return "container";
      }
   }

   /**
    * The containers this sorter reads out of right now.
    *
    * <p>Pull and push ask the same question from the same place, which is what keeps a sorter from
    * pulling something out of a chest and then handing it straight back: the answer is also the list
    * of positions the output must not use.
    */
   private static Set<BlockPos> sourcePositions(Level level, BlockPos pos, Entry e) {
      Set<BlockPos> sources = new LinkedHashSet<>();
      if (level == null || e == null) {
         return sources;
      }
      UUID owner = MachineManager.ownerAt(level, pos);
      boolean fromHoppers = MODE_HOPPERS.equals(e.mode) || MODE_BOTH.equals(e.mode);
      boolean fromContainers = MODE_CONTAINERS.equals(e.mode) || MODE_BOTH.equals(e.mode);
      List<BlockPos> around = new ArrayList<>();
      around.add(pos.above());
      for (Direction d : Direction.Plane.HORIZONTAL) {
         around.add(pos.relative(d));
      }
      for (BlockPos src : around) {
         Container c = containerAt(level, src);
         if (c == null) {
            continue;
         }
         // A sorter is never a source, whatever it looks like from outside: it is a hopper, so
         // "the containers beside it" and "hoppers" both include it, and a second sorter in the line
         // would quietly empty the first one's buffer - the items it is still deciding about, and the
         // items it is holding because their own destination is full. Feeding a sorter from a hopper
         // or a chest is the build; feeding it out of another sorter is two machines arguing.
         if (MachineManager.isItemSorter(level, src)) {
            continue;
         }
         boolean isHopper = c instanceof HopperBlockEntity;
         if (isHopper ? !fromHoppers : !fromContainers) {
            continue;
         }
         if (ChestShopManager.get(level, src) != null || !ClaimManager.canInteract(owner, level, src)) {
            continue;
         }
         sources.add(src);
         // A double chest is one container: its other half is a source too, or a sorter that reads
         // from one half would happily hand items straight back into the other. Reading still
         // dedupes on the canonical half (see tick), so this only ever widens the "do not put it
         // back" list, never the read.
         BlockPos partner = partnerOf(level, src);
         if (partner != null) {
            sources.add(partner);
         }
      }
      return sources;
   }

   /** "x,y,z" - the pinned target is a position, and it is written down as one. */
   private static String posKey(BlockPos pos) {
      return pos.getX() + "," + pos.getY() + "," + pos.getZ();
   }

   /** Reads back a written-down position, or null when it is absent or not three numbers. */
   public static BlockPos positionOf(String key) {
      return parsePos(key);
   }

   /** The private half of {@link #positionOf}. */
   private static BlockPos parsePos(String key) {
      if (key == null || key.isBlank()) {
         return null;
      }
      String[] parts = key.split(",");
      if (parts.length != 3) {
         return null;
      }
      try {
         return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
      } catch (NumberFormatException e) {
         return null;
      }
   }

   /**
    * Takes a filter list off a bag.
    *
    * <p>The integration the backpack asked for, done the way that cannot break: a sorter clicks
    * against a *container item* - the mod's Backpack, a shulker, a bundle - and adopts what is in
    * it as its filter. So the way a player tells a sorter what a farm should keep is to fill a pack
    * with those things and hit the block with it, rather than to click eighteen times.
    */
   public static void importFrom(ServerPlayer player, Level level, BlockPos pos, ItemStack bag) {
      List<String> found = contentsOf(bag);
      if (found.isEmpty()) {
         Chat.msg(player, "&7That bag has nothing in it to filter by.");
         return;
      }
      Entry e = entry(level, pos);
      int added = 0;
      for (String id : found) {
         if (e.size() >= FILTER_MAX) {
            break;
         }
         if (e.add(id)) {
            added++;
         }
      }
      save(player.level().getServer());
      Chat.msg(player, added > 0
         ? "&aThe sorter took &f" + added + "&a item type(s) off the bag (" + e.size() + " in the filter now)."
         : "&7Everything in that bag was already in the filter.");
      SoundUtil.play(player, ModSounds.TRANSFER);
   }

   /** The distinct item ids inside a container item, if it is one. Empty for anything else. */
   private static List<String> contentsOf(ItemStack bag) {
      List<String> out = new ArrayList<>();
      if (bag == null || bag.isEmpty()) {
         return out;
      }
      try {
         net.minecraft.world.item.component.ItemContainerContents contents =
            bag.get(net.minecraft.core.component.DataComponents.CONTAINER);
         if (contents == null) {
            return out;
         }
         for (ItemStack inside : contents.nonEmptyItemCopyStream().toList()) {
            if (!inside.isEmpty()) {
               String id = idOf(inside);
               if (!out.contains(id)) {
                  out.add(id);
               }
            }
         }
      } catch (Exception ignored) {
      }
      return out;
   }

   /** The item id the filter stores: the registry name, so a rename of anything cannot move it. */
   private static String idOf(ItemStack stack) {
      return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
   }

   /**
    * One pulse of the sorter. Runs on the hopper's own 8-tick cadence from the hopper mixin.
    *
    * <p>Pull from the container above first - a chest over a sorter is the build everybody makes -
    * then from the four sides; push out the face it is pointing at, and anything that will not fit
    * there is tried upwards and then sideways, so a sorter that has been turned the wrong way round
    * still works.
    */
   public static void tick(Level level, BlockPos pos) {
      if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof HopperBlockEntity hopper)) {
         return;
      }
      Entry e = entry(level, pos);
      if (e.items.isEmpty()) {
         return;
      }
      UUID owner = MachineManager.ownerAt(level, pos);
      Set<BlockPos> sources = sourcePositions(level, pos, e);
      // One pulse moves what the Super Hopper moves at this tier - a full stack to begin with -
      // rather than a fixed trickle; see pulseBudget. The budget is spent across the pull and the
      // push together, so a sorter never moves more in a pulse than the machine it is modelled on.
      int budget = pulseBudget(level, pos);
      boolean pulled = false;

      Set<BlockPos> read = new LinkedHashSet<>();
      for (BlockPos src : sources) {
         // Both halves of a double chest are in `sources` (so neither is a destination), but they
         // are one container: read it once, on the canonical half, or a double would give up two
         // stacks a pulse for the one it is.
         if (!read.add(canonicalPos(level, src))) {
            continue;
         }
         Container c = containerAt(level, src);
         if (c == null || c == hopper) {
            continue;
         }
         for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !e.accepts(s)) {
               continue;
            }
            ItemStack take = s.copyWithCount(Math.min(s.getCount(), budget));
            int moved = insert(hopper, take, budget);
            if (moved > 0) {
               s.shrink(moved);
               budget -= moved;
               c.setChanged();
               hopper.setChanged();
               pulled = true;
            }
            break;
         }
      }

      // The containers touching the block are what a player can see working, so they are asked
      // first; a marked container across the base is the fallback, and one stack moves per pulse
      // either way - the sorter keeps a steady rate instead of draining a remote chest the tick it
      // is wired up.
      if (!pulled) {
         pulled = pullFromLinks(level, pos, hopper, owner, e, budget);
      }

      boolean pushed = push(level, pos, hopper, owner, sources, budget);
      // One pulse per working sorter, spent from the server-wide machine particle budget - see
      // MachineVfx for why the sparkle is metered rather than free.
      if (pulled || pushed) {
         MachineVfx.pulse(level, pos, MachineManager.TYPE_ITEM_SORTER);
      }
   }

   /**
    * Takes one stack out of a marked container this sorter is linked to.
    *
    * <p>This is the remote half of the input: the tags are names, so a sorter follows whichever
    * container carries the name now. Everything else is the same rule as a container beside it - the
    * item has to be one the sorter keeps, a sorter is never a source, and one stack moves per pulse.
    *
    * <p>A tag whose container is in an unloaded chunk simply finds nothing: there is no block entity
    * to ask. Loading the chunk is what a chunk anchor or a player standing there is for, and the
    * window says so rather than the machine pretending.
    */
   private static boolean pullFromLinks(Level level, BlockPos pos, HopperBlockEntity hopper, UUID owner, Entry e, int cap) {
      String dimension = level.dimension().identifier().toString();
      for (String tag : e.links()) {
         SorterLinks.Link link = SorterLinks.find(tag);
         if (link == null || !link.dimension().equals(dimension) || !SorterLinks.inReach(pos, link.pos())) {
            continue;
         }
         if (owner != null && link.owner() != null && !link.owner().equals(owner)) {
            continue;
         }
         BlockPos at = link.pos();
         Container c = containerAt(level, at);
         if (at.equals(pos) || c == null || c == hopper) {
            continue;
         }
         if (MachineManager.isItemSorter(level, at)
            || ChestShopManager.get(level, at) != null
            || !ClaimManager.canInteract(owner, level, at)) {
            continue;
         }
         for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !e.accepts(s)) {
               continue;
            }
            ItemStack take = s.copyWithCount(Math.min(s.getCount(), cap));
            int moved = insert(hopper, take, cap);
            if (moved > 0) {
               s.shrink(moved);
               c.setChanged();
               hopper.setChanged();
               return true;
            }
            break;
         }
      }
      return false;
   }

   /**
    * One pulse of a Transfer Hopper: the sorter's movement, with no filter.
    *
    * <p>It pulls from every container around it and from every tagged container it is linked to,
    * keeps nothing back, and hands everything on - to the tagged container it is pointed at as an
    * output if it has one, otherwise to the way it faces and then around. Where a sorter asks "is
    * this mine?", a transfer hopper asks "is there room?": the same engine, one question removed.
    * A sorter or another transfer hopper is never a source, for the same reason it never is for a
    * sorter - two machines feeding each other drain the buffer, not the chest.
    *
    * <p>The output tag is an <i>output and nothing else</i>. That is the whole difference between it
    * and an input link, and it was not true before: a chest tagged as the output was still read from
    * if it happened to stand beside the hopper, so the one build the tag exists for - "send it all to
    * this chest" - looked like a hopper picking the chest up and putting it back down. Now the tag's
    * container is struck off the source list before anything is read, whoever set it. See
    * {@link #outTagTarget}.
    *
    * <p>Handing to another Transfer Hopper is done with the output tag too, and only with it. Two of
    * them standing beside each other on the automatic route would pass the same stack back and forth
    * every pulse - the buffer never settles, nothing arrives, and both sparkle as if they were
    * working. A chain is a decision, so it is written down: tag the next hopper and point this one's
    * output at it.
    */
   public static void tickTransfer(Level level, BlockPos pos) {
      tickTransfer(level, pos, false);
   }

   /**
    * One pulse of an Overflow Hopper: the Transfer Hopper with its tag moved to the end of the list.
    *
    * <p>The build it exists for is the one every farm ends up with. A chest fills, the hopper feeding
    * it is still holding a stack, and from that pulse on nothing moves anywhere again: the farm backs
    * up, the hopper sits full, and the only fix is a player walking over to empty the chest. This is
    * the same machine with one rule changed - the container on its ordinary route is filled first,
    * and a stack that the ordinary route will not take is offered to the output tag instead of being
    * held. So the first chest stays full and the surplus goes to the second one, which is what a
    * player means by "when this is full, use that".
    *
    * <p>"Will not take" is decided by trying it, never by counting slots: the pulse inserts the
    * stack into each destination in turn and stops at the first that accepts any of it. That is the
    * honest test, and it is why a chest with an empty slot but no room for this particular item
    * still spills over - see {@link #fillingChestFull} for the slot test the window reports.
    *
    * <p>The tag is still a sink: the container it names is struck off the source list exactly as it
    * is for a Transfer Hopper, so an Overflow Hopper whose spare chest stands right beside it does
    * not read back what it just spilled. And it is still a chain: point it at another Transfer
    * Hopper, or at another Overflow Hopper, and the surplus is carried on down the line.
    */
   public static void tickOverflow(Level level, BlockPos pos) {
      tickTransfer(level, pos, true);
   }

   /**
    * The one engine behind both transfer hoppers. {@code overflow} decides where the output tag sits
    * in the destination list, and whether a destination that takes nothing is the end of the pulse or
    * just the next thing to skip - which together are the entire difference between the two blocks.
    */
   private static void tickTransfer(Level level, BlockPos pos, boolean overflow) {
      if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof HopperBlockEntity hopper)) {
         return;
      }
      Entry e = entry(level, pos);
      UUID owner = MachineManager.ownerAt(level, pos);
      // Resolved once, because it changes both ends of the pulse: what must not be read from, and
      // what must be offered first.
      BlockPos outTagPos = outTagTarget(level, pos, e, owner);
      Set<BlockPos> sources = new LinkedHashSet<>();
      List<BlockPos> around = new ArrayList<>();
      around.add(pos.above());
      for (Direction d : Direction.Plane.HORIZONTAL) {
         around.add(pos.relative(d));
      }
      for (BlockPos src : around) {
         Container c = containerAt(level, src);
         if (c == null || c == hopper) {
            continue;
         }
         if (MachineManager.isItemSorter(level, src) || MachineManager.isTransferFamily(level, src)) {
            continue;
         }
         // The output tag is a destination, never a source. "Send everything to this chest" is not
         // also "take everything out of it", and a tagged chest left in the source list was read on
         // the same pulse it was written to.
         if (isSameContainer(level, src, outTagPos)) {
            continue;
         }
         if (ChestShopManager.get(level, src) != null || !ClaimManager.canInteract(owner, level, src)) {
            continue;
         }
         sources.add(src);
         BlockPos partner = partnerOf(level, src);
         if (partner != null) {
            sources.add(partner);
         }
      }

      // The same budget the Super Hopper moves on, spent across this hopper's pull and push in one
      // pulse. See pulseBudget: a Transfer Hopper is a hopper, and a player who points it at a chest
      // wants the chest moved, not a trickle.
      int budget = pulseBudget(level, pos);
      boolean pulled = false;
      Set<BlockPos> read = new LinkedHashSet<>();
      for (BlockPos src : sources) {
         if (!read.add(canonicalPos(level, src))) {
            continue;
         }
         Container c = containerAt(level, src);
         if (c == null || c == hopper) {
            continue;
         }
         for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) {
               continue;
            }
            ItemStack take = s.copyWithCount(Math.min(s.getCount(), budget));
            int moved = insert(hopper, take, budget);
            if (moved > 0) {
               s.shrink(moved);
               budget -= moved;
               c.setChanged();
               hopper.setChanged();
               pulled = true;
            }
            break;
         }
      }
      if (!pulled) {
         pullFromLinksTransfer(level, pos, hopper, owner, e, outTagPos, budget);
      }
      // What the hopper is holding goes to its output tag if it has one, and otherwise follows its
      // local output setting - the same route a sorter's unbound items take.
      //
      // Which end of that list the tag sits on is the whole of the difference between the two
      // hoppers. A Transfer Hopper's tag is its destination, so it is tried first and nothing else is
      // looked at but a stack the tag itself would not take. An Overflow Hopper's tag is its
      // spillway, so the ordinary route comes first and only a stack the chest in front of it refuses
      // ever reaches the tag. See tickOverflow.
      List<BlockPos> targets = new ArrayList<>();
      if (outTagPos != null && !overflow) {
         targets.add(outTagPos);
      }
      for (BlockPos at : defaultTargets(level, pos, e, sources)) {
         // The automatic route never hands to another transfer-family machine. The explicit output
         // tag is added above (or below), so a chain is still one click to build; what this stops is
         // two of them beside each other swapping the same stack back and forth forever.
         if (isSameContainer(level, at, outTagPos) || MachineManager.isTransferFamily(level, at)) {
            continue;
         }
         targets.add(at);
      }
      if (outTagPos != null && overflow) {
         targets.add(outTagPos);
      }

      boolean searching = false;
      for (int i = 0; i < hopper.getContainerSize(); i++) {
         searching = searching || !hopper.getItem(i).isEmpty();
      }
      boolean pushed = false;
      if (searching) {
         for (int i = 0; i < hopper.getContainerSize(); i++) {
            ItemStack s = hopper.getItem(i);
            if (s.isEmpty()) {
               continue;
            }
            for (BlockPos dst : targets) {
               if (dst.equals(pos) || ChestShopManager.get(level, dst) != null) {
                  continue;
               }
               Container c = containerAt(level, dst);
               if (c == null || c == hopper || !ClaimManager.canInteract(owner, level, dst)) {
                  continue;
               }
               int sent = insert(c, s, budget);
               if (sent > 0) {
                  s.shrink(sent);
                  budget -= sent;
                  if (s.isEmpty()) {
                     hopper.setItem(i, ItemStack.EMPTY);
                  }
                  c.setChanged();
                  hopper.setChanged();
                  pushed = true;
               }
               // One destination per stack per pulse - a plain Transfer Hopper offers a stack to the
               // first place on its list and lets the next pulse try again, exactly like a hopper.
               // An Overflow Hopper keeps walking its list while nothing will take the stack, which
               // is how a full first chest reaches the tag behind it within one pulse.
               if (sent > 0 || !overflow) {
                  break;
               }
            }
         }
      }
      if (pulled || pushed) {
         MachineVfx.pulse(level, pos, overflow ? MachineManager.TYPE_OVERFLOW_HOPPER : MachineManager.TYPE_TRANSFER_HOPPER);
      }
   }

   /**
    * The unfiltered twin of {@link #pullFromLinks}: a transfer hopper takes everything.
    *
    * <p>{@code outTagPos} is the container this hopper is pointed at as an output, and it is not read
    * from even when the same tag is also named as an input. Setting an output takes the tag off the
    * input list (see {@link #setOutTag}), so this only matters for a setting written down before that
    * rule existed - and a chest that is drained by the same hopper that fills it is a chest that never
    * fills, which is exactly the bug this pins.
    */
   private static boolean pullFromLinksTransfer(
      Level level, BlockPos pos, HopperBlockEntity hopper, UUID owner, Entry e, BlockPos outTagPos, int cap
   ) {
      String dimension = level.dimension().identifier().toString();
      for (String tag : e.links()) {
         SorterLinks.Link link = SorterLinks.find(tag);
         if (link == null || !link.dimension().equals(dimension) || !SorterLinks.inReach(pos, link.pos())) {
            continue;
         }
         if (owner != null && link.owner() != null && !link.owner().equals(owner)) {
            continue;
         }
         BlockPos at = link.pos();
         Container c = containerAt(level, at);
         if (at.equals(pos) || c == null || c == hopper) {
            continue;
         }
         if (isSameContainer(level, at, outTagPos)) {
            continue;
         }
         if (MachineManager.isItemSorter(level, at)
            || MachineManager.isTransferFamily(level, at)
            || ChestShopManager.get(level, at) != null
            || !ClaimManager.canInteract(owner, level, at)) {
            continue;
         }
         for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) {
               continue;
            }
            ItemStack take = s.copyWithCount(Math.min(s.getCount(), cap));
            int moved = insert(hopper, take, cap);
            if (moved > 0) {
               s.shrink(moved);
               c.setChanged();
               hopper.setChanged();
               return true;
            }
            break;
         }
      }
      return false;
   }

   /**
    * Points this sorter's input at one tagged container, or takes it off that tag.
    *
    * <p>The tag is what is stored, never the position: an id is a name for a place, so moving the
    * mark to a different chest moves every sorter that names it - which is the whole point of having
    * ids instead of coordinates, and why nothing here is rewritten when a marked chest is broken.
    */
   public static void toggleLink(ServerPlayer player, Level level, BlockPos pos, String tag) {
      Entry e = entry(level, pos);
      SorterLinks.Link link = SorterLinks.find(tag);
      if (link == null) {
         Chat.msg(player, "&cThat tag no longer exists - tag a container with a Sorter Tag first.");
         return;
      }
      // A tag is one job or the other. Clicking one that is already this hopper's output is refused
      // rather than quietly becoming both ends: a chest read from and written to on the same pulse
      // moves its own contents in a circle, which is the shape of the bug this whole rule set is
      // about. Shift-clicking the row is how the output comes off.
      if (tag.equals(e.outTag())) {
         Chat.msg(player, MachineManager.isOverflowHopper(level, pos)
            ? "&cThat tag is where this hopper's &foverflow&c goes, so it is not an input too. Shift-click the tag to stop spilling into it, then click to pull from it."
            : "&cThat tag is this hopper's &foutput&c, so it is not an input too. Shift-click the tag to stop sending there, then click to pull from it.");
         return;
      }
      if (e.isLinked(tag)) {
         e.links().remove(tag);
         save(player.level().getServer());
         Chat.msg(player, "&7This sorter no longer pulls from &f" + tag + "&7.");
         SoundUtil.play(player, ModSounds.TRANSFER);
         return;
      }
      if (e.links().size() >= SorterLinks.MAX_LINKS) {
         Chat.msg(player, "&cThis sorter can follow " + SorterLinks.MAX_LINKS + " tagged containers. Untick one first.");
         return;
      }
      e.links().add(tag);
      save(player.level().getServer());
      Chat.msg(
         player,
         "&aThis sorter now pulls from &f" + tag + " &aat &f" + link.pos().getX() + ", " + link.pos().getY() + ", " + link.pos().getZ()
            + " &7(the " + containerName(level, link.pos()) + " there)."
      );
      SoundUtil.play(player, ModSounds.TRANSFER);
   }

   /**
    * Steps a Transfer Hopper's output tag through the tags in reach, then off again.
    *
    * <p>Input links are a list - a base has many sources - but the output is one place, so this one
    * verb walks the tags nearest first and then clears, which is the same shape as the default
    * output's own pin cycle.
    */
   public static void cycleOutTag(ServerPlayer player, Level level, BlockPos pos) {
      Entry e = entry(level, pos);
      List<SorterLinks.Link> reach = SorterLinks.reachable(level, pos, MachineManager.ownerAt(level, pos));
      if (reach.isEmpty()) {
         Chat.msg(player, "&cNo tagged container is in reach - tag one with a Sorter Tag first.");
         return;
      }
      int index = -1;
      for (int i = 0; i < reach.size(); i++) {
         if (reach.get(i).id().equals(e.outTag)) {
            index = i;
            break;
         }
      }
      boolean overflow = MachineManager.isOverflowHopper(level, pos);
      if (index + 1 >= reach.size()) {
         e.setOutTag(null);
         save(player.level().getServer());
         Chat.msg(player, overflow
            ? "&7Its overflow is off - anything that will not fit now stays in the hopper."
            : "&7Its output is back to the way it faces.");
         SoundUtil.play(player, ModSounds.TRANSFER);
         return;
      }
      SorterLinks.Link next = reach.get(index + 1);
      // Stepping onto a tag that was an input takes it off the input list, the same as setting it
      // from the row does: the output is never also read from. See setOutTag.
      e.links().remove(next.id());
      e.setOutTag(next.id());
      save(player.level().getServer());
      Chat.msg(player, overflow
         ? "&aWhatever will not fit in front of this hopper now spills over to &f" + next.id() + "&a."
         : "&aThis hopper now sends everything to &f" + next.id() + "&a.");
      SoundUtil.play(player, ModSounds.TRANSFER);
   }

   /**
    * Names one tagged container as a Transfer Hopper's output - or takes that name off again.
    *
    * <p>The transfer hopper is the one machine with tags at both ends: a list of places to pull
    * <i>from</i> and one place to put everything <i>to</i>. The window's own "Send to" button steps
    * through the reachable tags one at a time, which is fine for one or two and tedious for six; this
    * is the same decision made directly, from the tag the player is already looking at.
    */
   public static void setOutTag(ServerPlayer player, Level level, BlockPos pos, String tag) {
      Entry e = entry(level, pos);
      boolean overflow = MachineManager.isOverflowHopper(level, pos);
      if (tag != null && tag.equals(e.outTag())) {
         e.setOutTag(null);
         save(player.level().getServer());
         Chat.msg(player, overflow
            ? "&7Its overflow is off - anything that will not fit now stays in the hopper."
            : "&7Its output is back to the way it faces.");
         SoundUtil.play(player, ModSounds.TRANSFER);
         return;
      }
      SorterLinks.Link link = SorterLinks.find(tag);
      if (link == null) {
         Chat.msg(player, "&cThat tag no longer exists - tag a container with a Sorter Tag first.");
         return;
      }
      // One tag is one job. Setting a tag as the output takes it off the input list, so the chest it
      // names can never be read from and written to on the same pulse - and so the window can never
      // show the same tag lit up twice with no way to tell which end is which.
      e.links().remove(tag);
      e.setOutTag(tag);
      save(player.level().getServer());
      Chat.msg(player, overflow
         ? "&aEverything that will not fit in front of this hopper now spills over to &f#" + tag + " &7(the "
            + containerName(level, link.pos()) + " at " + link.pos().getX() + ", " + link.pos().getY() + ", "
            + link.pos().getZ() + ")."
         : "&aEverything this hopper moves now goes to &f#" + tag + " &7(the "
            + containerName(level, link.pos()) + " at " + link.pos().getX() + ", " + link.pos().getY() + ", "
            + link.pos().getZ() + ").");
      SoundUtil.play(player, ModSounds.TRANSFER);
   }

   /** Drops every remote tag from one sorter. */
   public static void clearLinks(ServerPlayer player, Level level, BlockPos pos) {
      Entry e = entry(level, pos);
      if (e.links().isEmpty()) {
         Chat.msg(player, "&7This sorter isn't following any tags.");
         return;
      }
      int had = e.links().size();
      e.links().clear();
      save(player.level().getServer());
      Chat.msg(player, "&7Cleared &f" + had + "&7 remote input(s).");
      SoundUtil.play(player, ModSounds.TRANSFER);
   }

   /**
    * Hands the sorter's own contents on, one stack at a time.
    *
    * <p>Per stack and not per pulse, because each item can be pointed at its own container: a sorter
    * whose list says iron goes to the barrel and gold goes the usual way has to move the two of them
    * to two different places in the same tick. A stack whose destination refuses it simply stays put
    * and is tried again next pulse - which is what any hopper in front of a full chest does, and what
    * keeps "the iron goes to that barrel" true rather than "the iron goes there until it is
    * inconvenient".
    */
   private static boolean push(
      Level level, BlockPos pos, HopperBlockEntity hopper, UUID owner, Set<BlockPos> sources, int budget
   ) {
      // `sources` is the containers this sorter reads from. It is not a blanket ban on destinations -
      // an explicit choice (an item bound to a container, or the default output pinned at one) is
      // obeyed wherever it points, because a person made it. It only keeps the automatic routes from
      // pulling something out of a chest and handing it straight back, which is a stir rather than a
      // sort. See defaultTargets.
      boolean anything = false;
      for (int i = 0; i < hopper.getContainerSize(); i++) {
         if (!hopper.getItem(i).isEmpty()) {
            anything = true;
            break;
         }
      }
      if (!anything) {
         return false;
      }
      boolean moved = false;
      Entry e = entry(level, pos);
      for (int i = 0; i < hopper.getContainerSize(); i++) {
         ItemStack s = hopper.getItem(i);
         if (s.isEmpty()) {
            continue;
         }
         for (BlockPos dst : outputTargets(level, pos, e, s, owner, sources)) {
            if (dst.equals(pos) || ChestShopManager.get(level, dst) != null) {
               continue;
            }
            Container c = containerAt(level, dst);
            if (c == null || c == hopper) {
               continue;
            }
            if (!ClaimManager.canInteract(owner, level, dst)) {
               continue;
            }
            int sent = insert(c, s, budget);
            if (sent > 0) {
               s.shrink(sent);
               budget -= sent;
               if (s.isEmpty()) {
                  hopper.setItem(i, ItemStack.EMPTY);
               }
               c.setChanged();
               hopper.setChanged();
               moved = true;
            }
            // One destination per stack per pulse, whether or not it had room: a stack that is bound
            // somewhere is not also offered to the default route on the way past.
            break;
         }
      }
      return moved;
   }

   /**
    * Where one stack may go, in the order to try.
    *
    * <p>An item that has been given a container of its own goes there and nowhere else. Everything
    * else follows the sorter's default route. A binding the world has taken away - the barrel was
    * broken, or the land it stood on changed hands - is dropped rather than obeyed forever, because
    * a machine holding on to a promise nobody can see is the same as a machine that has stopped.
    */
   private static List<BlockPos> outputTargets(
      Level level, BlockPos pos, Entry e, ItemStack stack, UUID owner, Set<BlockPos> sources
   ) {
      if (stack != null && !stack.isEmpty()) {
         String id = idOf(stack);
         BlockPos bound = parsePos(e.destination(id));
         if (bound != null) {
            if (isReachableContainer(level, bound, owner) && !bound.equals(pos)) {
               return List.of(bound);
            }
            // The container it was pointed at is gone or out of reach. Forget the binding rather
            // than hold items forever for a promise nobody can see; the window shows each entry's
            // destination and says when it has been lost.
            e.bind(id, null);
            save(level.getServer());
         }
      }
      return defaultTargets(level, pos, e, sources);
   }

   /**
    * The sorter's own output setting, in the order to try.
    *
    * <p>Auto is the hopper's own answer - the face it points at, then up, then around - which is
    * forgiving about a sorter that has been turned the wrong way round. Nearby is the nearest
    * container in reach, so a row of barrels can be filled from one block. Pinned is one container
    * and no other, which is a promise: a player who aimed their redstone at a barrel wants it in
    * that barrel even if something nearer appears later.
    */
   private static List<BlockPos> defaultTargets(Level level, BlockPos pos, Entry e, Set<BlockPos> sources) {
      List<BlockPos> targets = new ArrayList<>();
      if (OUT_PINNED.equals(e.out)) {
         // Pinned on purpose, so it is obeyed even when the container is also a source.
         BlockPos pinned = parsePos(e.target);
         if (pinned != null) {
            targets.add(pinned);
         }
         return targets;
      }
      if (OUT_NEARBY.equals(e.out)) {
         for (BlockPos at : outputCandidates(level, pos)) {
            if (!sources.contains(at)) {
               targets.add(at);
            }
         }
         return targets;
      }
      net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
      Direction facing = state.getBlock() instanceof HopperBlock ? state.getValue(HopperBlock.FACING) : Direction.DOWN;
      List<BlockPos> around = new ArrayList<>();
      around.add(pos.relative(facing));
      around.add(pos.above());
      for (Direction d : Direction.Plane.HORIZONTAL) {
         around.add(pos.relative(d));
      }
      for (BlockPos at : around) {
         if (!sources.contains(at)) {
            targets.add(at);
         }
      }
      return targets;
   }

   /**
    * Items one pulse of a sorter or a transfer hopper may move: the same budget the Super Hopper
    * moves on, so a machine pointed at a chest moves the chest rather than a trickle.
    *
    * <p>Tier one - which is what every Item Sorter and Transfer Hopper is, since neither is
    * upgradeable - is a full stack a pulse, and the figure would grow with the hopper tier if that
    * ever changed. {@link #PULL_PER_PULSE} stays the floor, so nothing here can be slower than the
    * machine was before the budget was introduced.
    */
   private static int pulseBudget(Level level, BlockPos pos) {
      return Math.max(PULL_PER_PULSE, MachineTuning.hopperBudget(MachineManager.keyFor(level, pos)));
   }

   /**
    * Moves as much of {@code stack} into the target as fits, capped at {@code cap} items so one pulse
    * spends a budget rather than draining whatever it is standing next to. See {@link #pulseBudget}.
    */
   private static int insert(Container target, ItemStack stack, int cap) {
      int moved = 0;
      for (int t = 0; t < target.getContainerSize() && moved < cap && !stack.isEmpty(); t++) {
         ItemStack cur = target.getItem(t);
         if (cur.isEmpty()) {
            int take = Math.min(stack.getCount(), cap - moved);
            target.setItem(t, stack.copyWithCount(take));
            moved += take;
            stack.shrink(take);
         } else if (ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() < cur.getMaxStackSize()) {
            int room = Math.min(cur.getMaxStackSize() - cur.getCount(), Math.min(stack.getCount(), cap - moved));
            if (room <= 0) {
               continue;
            }
            cur.grow(room);
            stack.shrink(room);
            moved += room;
         }
         target.setChanged();
      }
      return moved;
   }

   // Nothing here makes a sound, and that is deliberate. The sorter used to play a pickup pop every
   // other second while it worked, which on a machine that runs unattended all day is a metronome in
   // the base - and the `% 40` gate that was meant to keep it rare read a frozen zero in every realm
   // (see ServerClock), so there it played on every pulse instead. The sparkle over the block stays:
   // particles are how a player sees a sorter working without hearing it from across the base.
}
