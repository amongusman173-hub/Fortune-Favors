package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * The Backpack Kitchen: the Portable Furnace and the Portable Campfire, fused into the pack.
 *
 * <p>Both were machines you had to put down - which is a strange thing to say about a portable
 * furnace, because the trip it exists for is the one where you are not standing still. Fused into a
 * backpack, they cook out of the pack: the ore you just mined and the beef you just killed go in
 * where they are and come out cooked, wherever you are.
 *
 * <p>The two are not the same machine and this keeps them apart. A furnace smelts anything and
 * burns fuel out of the pack, a unit at a time. A campfire is always lit, wants nothing, cooks food
 * only, and takes half again as long per item. A player who owns both has a real choice rather than
 * a better one.
 *
 * <p>The contents live where the pack's own storage lives - the {@code ff_bp} list in its custom
 * data - so the kitchen reads and writes the same items the pack shows, and nothing is duplicated
 * into a second inventory that could disagree with it.
 */
public final class BackpackKitchen {
   /** Tag on the backpack's custom data holding which kitchen is fused in. */
   public static final String KIND_TAG = "ff_kitchen";
   /** Tag holding how many smelts the last piece of fuel bought. */
   public static final String BURN_TAG = "ff_kitchen_burn";
   /** The pack's own storage list, shared with BackpackMenu - see its saveContents. */
   public static final String CONTENTS_TAG = "ff_bp";
   /** A blast furnace's fire, folded into the pack. Smelts anything, burns what you carry. */
   public static final String KIND_FURNACE = "furnace";
   /** A smoker's fire, folded into the pack. Always lit, cooks food only, and slower per item. */
   public static final String KIND_CAMPFIRE = "campfire";
   /** How many ticks one item takes in the fused furnace. */
   public static final int FURNACE_TICKS = 20;
   /** How many ticks one item takes on the fused campfire - half again as long, and free. */
   public static final int CAMPFIRE_TICKS = 30;
   /** How many items the window's own button cooks in one go. */
   public static final int BURST = 16;

   private static final Map<Item, ItemStack> SMELTS = new java.util.HashMap<>();
   private static final Map<Item, ItemStack> CAMPFIRE = new java.util.HashMap<>();
   private static final Map<Item, Integer> FUEL = new java.util.HashMap<>();

   private BackpackKitchen() {
   }

   private static void ore(Item raw, Item cooked) {
      SMELTS.put(raw, new ItemStack(cooked));
   }

   private static void food(Item raw, Item cooked) {
      ItemStack out = new ItemStack(cooked);
      SMELTS.put(raw, out);
      CAMPFIRE.put(raw, out);
   }

   static {
      // Metals and stone: the furnace's own list, written out so nothing a datapack does can change
      // what a player's backpack produces under them.
      ore(Items.IRON_ORE, Items.IRON_INGOT);
      ore(Items.DEEPSLATE_IRON_ORE, Items.IRON_INGOT);
      ore(Items.RAW_IRON, Items.IRON_INGOT);
      ore(Items.GOLD_ORE, Items.GOLD_INGOT);
      ore(Items.DEEPSLATE_GOLD_ORE, Items.GOLD_INGOT);
      ore(Items.NETHER_GOLD_ORE, Items.GOLD_INGOT);
      ore(Items.RAW_GOLD, Items.GOLD_INGOT);
      ore(Items.COPPER_ORE, Items.COPPER_INGOT);
      ore(Items.DEEPSLATE_COPPER_ORE, Items.COPPER_INGOT);
      ore(Items.RAW_COPPER, Items.COPPER_INGOT);
      ore(Items.ANCIENT_DEBRIS, Items.NETHERITE_SCRAP);
      ore(Items.SAND, Items.GLASS);
      ore(Items.RED_SAND, Items.GLASS);
      ore(Items.COBBLESTONE, Items.STONE);
      ore(Items.COBBLED_DEEPSLATE, Items.DEEPSLATE);
      ore(Items.STONE, Items.SMOOTH_STONE);
      ore(Items.CLAY_BALL, Items.BRICK);
      ore(Items.CLAY, Items.TERRACOTTA);
      ore(Items.NETHERRACK, Items.NETHER_BRICK);
      ore(Items.WET_SPONGE, Items.SPONGE);
      ore(Items.CACTUS, Items.DYE.pick(net.minecraft.world.item.DyeColor.GREEN));
      ore(Items.SEA_PICKLE, Items.DYE.pick(net.minecraft.world.item.DyeColor.LIME));
      ore(Items.CHORUS_FRUIT, Items.POPPED_CHORUS_FRUIT);
      // ...and everything a campfire can do, which is food.
      food(Items.POTATO, Items.BAKED_POTATO);
      food(Items.KELP, Items.DRIED_KELP);
      food(Items.BEEF, Items.COOKED_BEEF);
      food(Items.PORKCHOP, Items.COOKED_PORKCHOP);
      food(Items.CHICKEN, Items.COOKED_CHICKEN);
      food(Items.MUTTON, Items.COOKED_MUTTON);
      food(Items.RABBIT, Items.COOKED_RABBIT);
      food(Items.COD, Items.COOKED_COD);
      food(Items.SALMON, Items.COOKED_SALMON);
      food(Items.ROTTEN_FLESH, Items.LEATHER);
      // Fuel, in the units a furnace would have burned them for. Only the ones a player carries.
      FUEL.put(Items.COAL, 8);
      FUEL.put(Items.CHARCOAL, 8);
      FUEL.put(Items.COAL_BLOCK, 72);
      FUEL.put(Items.DRIED_KELP_BLOCK, 72);
      FUEL.put(Items.BLAZE_ROD, 12);
      FUEL.put(Items.LAVA_BUCKET, 100);
      FUEL.put(Items.STICK, 1);
      FUEL.put(Items.BAMBOO, 1);
      FUEL.put(Items.DEAD_BUSH, 1);
      FUEL.put(Items.OAK_PLANKS, 2);
      FUEL.put(Items.OAK_LOG, 2);
      FUEL.put(Items.STRIPPED_OAK_LOG, 2);
      FUEL.put(Items.OAK_WOOD, 2);
      FUEL.put(Items.DRIED_KELP, 1);
   }

   /** Which kitchen is fused into this pack, or null when there is none. */
   public static String kindOf(ItemStack pack) {
      if (pack == null || pack.isEmpty() || !ModItems.isBackpack(pack)) {
         return null;
      }
      CustomData data = pack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return null;
      }
      String kind = data.copyTag().getStringOr(KIND_TAG, "");
      return KIND_FURNACE.equals(kind) || KIND_CAMPFIRE.equals(kind) ? kind : null;
   }

   /** True when this pack cooks. */
   public static boolean hasKitchen(ItemStack pack) {
      return kindOf(pack) != null;
   }

   /** The display name of a kind, for lore and messages. */
   public static String label(String kind) {
      return KIND_CAMPFIRE.equals(kind) ? "Portable Campfire" : "Portable Furnace";
   }

   /** Fuses a kitchen into a pack. The caller owns consuming the machine item. */
   public static void attach(ItemStack pack, String kind) {
      if (pack == null || pack.isEmpty()) {
         return;
      }
      CustomData.update(DataComponents.CUSTOM_DATA, pack, tag -> tag.putString(KIND_TAG, kind));
   }

   /** Which kind a machine item would fuse into a pack, or null when it is not a kitchen machine. */
   public static String kindFor(ItemStack machine) {
      if (machine == null || machine.isEmpty()) {
         return null;
      }
      String type = ModItems.typeOf(machine);
      if (MachineManager.TYPE_PORTABLE_FURNACE.equals(type)) {
         return KIND_FURNACE;
      }
      if (MachineManager.TYPE_PORTABLE_CAMPFIRE.equals(type)) {
         return KIND_CAMPFIRE;
      }
      return null;
   }

   /** What one item comes out as, or EMPTY when this kitchen will not cook it. */
   public static ItemStack smelt(String kind, ItemStack in) {
      if (in == null || in.isEmpty() || kind == null) {
         return ItemStack.EMPTY;
      }
      ItemStack out = KIND_CAMPFIRE.equals(kind) ? CAMPFIRE.get(in.getItem()) : SMELTS.get(in.getItem());
      return out == null ? ItemStack.EMPTY : out.copy();
   }

   /** True when the furnace would cook this and the campfire would not - the `why` in the window. */
   public static boolean furnaceOnly(ItemStack in) {
      return in != null && !in.isEmpty() && SMELTS.containsKey(in.getItem()) && !CAMPFIRE.containsKey(in.getItem());
   }

   /** How many smelts one of these is worth as fuel; 0 when it is not fuel at all. */
   public static int fuelUnits(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return 0;
      }
      Integer units = FUEL.get(stack.getItem());
      return units == null ? 0 : units;
   }

   /** How much fuel is banked in this pack right now. */
   public static int burn(ItemStack pack) {
      CustomData data = pack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 0 : data.copyTag().getIntOr(BURN_TAG, 0);
   }

   private static void setBurn(ItemStack pack, int units) {
      CustomData.update(DataComponents.CUSTOM_DATA, pack, tag -> {
         if (units <= 0) {
            tag.remove(BURN_TAG);
         } else {
            tag.putInt(BURN_TAG, units);
         }
      });
   }

   /** The items inside a pack, in the order the pack holds them. Copies, never the live stacks. */
   public static List<ItemStack> contents(ServerPlayer player, ItemStack pack) {
      List<ItemStack> out = new ArrayList<>();
      if (pack == null || pack.isEmpty() || player == null) {
         return out;
      }
      CustomData data = pack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return out;
      }
      Tag stored = data.copyTag().get(CONTENTS_TAG);
      if (!(stored instanceof ListTag list)) {
         return out;
      }
      RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, player.registryAccess());
      for (Tag entry : list) {
         DataResult<ItemStack> parsed = ItemStack.OPTIONAL_CODEC.parse(ops, entry);
         parsed.result().ifPresent(stack -> {
            if (!stack.isEmpty()) {
               out.add(stack);
            }
         });
      }
      return out;
   }

   /** Writes a pack's contents back, dropping the empties. */
   public static void setContents(ServerPlayer player, ItemStack pack, List<ItemStack> items) {
      if (pack == null || pack.isEmpty() || player == null) {
         return;
      }
      ListTag list = new ListTag();
      RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, player.registryAccess());
      for (ItemStack stack : items) {
         if (stack == null || stack.isEmpty()) {
            continue;
         }
         DataResult<Tag> encoded = ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack);
         encoded.result().ifPresent(list::add);
      }
      CustomData.update(DataComponents.CUSTOM_DATA, pack, tag -> tag.put(CONTENTS_TAG, list));
   }

   /**
    * One cook: take the first thing the kitchen can cook out of the pack, and put the result back.
    *
    * <p>Everything that can refuse does, before anything is consumed: no item, no fuel, no room for
    * the result all leave the pack exactly as it was. The result prefers the slot the input came out
    * of, so a pack that was carrying sixteen raw beef comes back with sixteen cooked beef and no
    * rearrangement.
    *
    * @return what was cooked, or EMPTY when the kitchen did nothing at all
    */
   public static ItemStack cookOnce(ServerPlayer player, ItemStack pack) {
      String kind = kindOf(pack);
      if (kind == null) {
         return ItemStack.EMPTY;
      }
      List<ItemStack> items = contents(player, pack);
      int index = -1;
      ItemStack result = ItemStack.EMPTY;
      for (int i = 0; i < items.size(); i++) {
         ItemStack candidate = smelt(kind, items.get(i));
         if (!candidate.isEmpty()) {
            index = i;
            result = candidate;
            break;
         }
      }
      if (index < 0) {
         return ItemStack.EMPTY;
      }
      int burn = burn(pack);
      if (KIND_FURNACE.equals(kind)) {
         if (burn <= 0) {
            int fed = refuel(player, pack, items);
            if (fed <= 0) {
               return ItemStack.EMPTY;
            }
            burn = burn(pack);
         }
         if (burn <= 0) {
            return ItemStack.EMPTY;
         }
      }
      ItemStack in = items.get(index);
      // The result goes where the input was, so the pack does not shuffle itself while it works.
      ItemStack out = result.copyWithCount(in.getCount());
      items.set(index, out);
      setContents(player, pack, items);
      if (KIND_FURNACE.equals(kind)) {
         setBurn(pack, burn - 1);
      }
      return out;
   }

   /**
    * Burns the first usable fuel in the pack.
    *
    * <p>Fuel is taken out of the pack and its units are banked, so a stack of coal is eight smelts
    * and a bucket of lava is a hundred - the same arithmetic a furnace does, spent from what you are
    * carrying rather than from a slot beside a block.
    */
   private static int refuel(ServerPlayer player, ItemStack pack, List<ItemStack> items) {
      for (int i = 0; i < items.size(); i++) {
         ItemStack candidate = items.get(i);
         int units = fuelUnits(candidate);
         if (units <= 0) {
            continue;
         }
         if (candidate.getCount() <= 1) {
            items.remove(i);
         } else {
            candidate.shrink(1);
         }
         setContents(player, pack, items);
         setBurn(pack, units);
         return units;
      }
      return 0;
   }

   /**
    * One tick of a carried pack's kitchen.
    *
    * <p>Runs on the player's own tick and only while the pack is in their inventory: a pack open in
    * a menu has its contents rewritten from the menu's own container, and cooking into it there
    * would be a write the menu overwrites. So a pack on the back cooks, a pack on the table waits.
    *
    * @return what was cooked this tick, or EMPTY
    */
   public static ItemStack tick(ServerPlayer player) {
      ItemStack pack = carriedKitchen(player);
      if (pack.isEmpty()) {
         return ItemStack.EMPTY;
      }
      String kind = kindOf(pack);
      int period = KIND_CAMPFIRE.equals(kind) ? CAMPFIRE_TICKS : FURNACE_TICKS;
      if (player.level().getGameTime() % period != 0L) {
         return ItemStack.EMPTY;
      }
      ItemStack cooked = cookOnce(player, pack);
      if (!cooked.isEmpty() && player.level() instanceof ServerLevel server) {
         server.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, player.getX(), player.getY() + 1.2, player.getZ(), 2, 0.25, 0.2, 0.25, 0.005);
         if (server.getGameTime() % 100L == 0L) {
            server.playSound(null, player.getX(), player.getY(), player.getZ(),
               KIND_CAMPFIRE.equals(kind) ? SoundEvents.CAMPFIRE_CRACKLE : SoundEvents.FURNACE_FIRE_CRACKLE,
               SoundSource.PLAYERS, 0.35F, 1.3F);
         }
      }
      return cooked;
   }

   /** The pack whose kitchen should be running: the first one in the player's inventory. */
   private static ItemStack carriedKitchen(ServerPlayer player) {
      if (player == null) {
         return ItemStack.EMPTY;
      }
      ItemStack main = player.getMainHandItem();
      if (hasKitchen(main)) {
         return main;
      }
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (hasKitchen(stack)) {
            return stack;
         }
      }
      return ItemStack.EMPTY;
   }

   /** The pack this player carries a kitchen in, for the window and the commands. */
   public static ItemStack carried(ServerPlayer player) {
      return carriedKitchen(player);
   }

   /**
    * The window's own button: cooks as much as it can right now, up to {@link #BURST}.
    *
    * <p>A player who presses it gets an answer either way, because "nothing happened" is the one
    * result a button must never give: either it cooked, or it says which of the two things is
    * missing.
    *
    * @return a line to show the player, and whether anything cooked
    */
   public static String cookBurst(ServerPlayer player) {
      ItemStack pack = carriedKitchen(player);
      if (pack.isEmpty()) {
         return "&cNo backpack with a kitchen in it - carry the pack to cook with it.";
      }
      String kind = kindOf(pack);
      int cooked = 0;
      for (int i = 0; i < BURST; i++) {
         ItemStack result = cookOnce(player, pack);
         if (result.isEmpty()) {
            break;
         }
         cooked += result.getCount();
      }
      if (cooked > 0) {
         com.fortuneandfavors.util.SoundUtil.play(player, SoundEvents.FURNACE_FIRE_CRACKLE, 1.4F, 0.3F);
         return "&aThe " + label(kind).toLowerCase() + " cooked &f" + cooked + "&a item(s) out of the pack.";
      }
      boolean hasCookable = false;
      boolean hasFuel = KIND_CAMPFIRE.equals(kind);
      for (ItemStack in : contents(player, pack)) {
         if (!smelt(kind, in).isEmpty()) {
            hasCookable = true;
         }
      }
      for (ItemStack in : contents(player, pack)) {
         if (fuelUnits(in) > 0) {
            hasFuel = true;
         }
      }
      if (!hasCookable) {
         return KIND_CAMPFIRE.equals(kind)
            ? "&7The campfire cooks food only - there is nothing in the pack it can cook."
            : "&7Nothing in the pack smells like it would melt or cook.";
      }
      return "&cThe furnace is out of fuel - put a little coal or a few planks in the pack.";
   }

   /** The kitchen's own description, for the pack's window and its lore. */
   public static List<Component> status(ServerPlayer player, ItemStack pack) {
      String kind = kindOf(pack);
      List<Component> out = new ArrayList<>();
      if (kind == null) {
         out.add(Component.literal("§8No kitchen fused in."));
         return out;
      }
      out.add(Component.literal("§6" + label(kind) + "§7 - fused into this pack."));
      if (KIND_CAMPFIRE.equals(kind)) {
         out.add(Component.literal("§7Cooks §ffood§7 out of the pack, at no fuel cost."));
         out.add(Component.literal("§8A campfire is always lit."));
      } else {
         int banked = burn(pack);
         out.add(Component.literal("§7Smelts §fanything§7 out of the pack, burning"));
         out.add(Component.literal("§7fuel you are carrying."));
         out.add(Component.literal("§7Fuel banked: §f" + banked + "§7 smelt(s)."));
      }
      int cookable = 0;
      int fuel = 0;
      for (ItemStack in : contents(player, pack)) {
         if (!smelt(kind, in).isEmpty()) {
            cookable += in.getCount();
         }
         if (fuelUnits(in) > 0) {
            fuel += fuelUnits(in);
         }
      }
      out.add(Component.literal("§7Cookable in the pack: §f" + cookable + "§7 item(s)."));
      if (KIND_FURNACE.equals(kind)) {
         out.add(Component.literal("§7Fuel in the pack: §f" + fuel + "§7 smelt(s)."));
      }
      return out;
   }

   /** Hands a cooked item to the player, or drops it - the escape hatch for a full inventory. */
   public static void give(ServerPlayer player, ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         InventoryHelper.giveOrDrop(player, stack);
      }
   }

   /** A one-line summary for the pack's tooltip. */
   public static String loreLine(ItemStack pack) {
      String kind = kindOf(pack);
      return kind == null ? null : "§6Built-in " + label(kind) + "§7 - open the pack and click it to cook.";
   }
}
