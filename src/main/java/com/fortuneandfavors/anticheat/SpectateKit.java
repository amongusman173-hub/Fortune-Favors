package com.fortuneandfavors.anticheat;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.VanishManager;
import com.fortuneandfavors.menu.AntiCheatMenu;
import com.fortuneandfavors.util.Chat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;

/**
 * What a moderator is holding while they watch somebody.
 *
 * <p><b>The problem this solves is that watching a player and judging them are the same task,
 * and the tools for judging them were somewhere else.</b> A moderator who takes a spectate
 * arrives as a ghost with an empty hotbar, has to remember {@code /ff anticheat info}, has to
 * leave to type a punishment, and cannot tell the player's own gear from the ground - which is
 * why every verdict here is a right-click on a labelled item instead.
 *
 * <p><b>Their inventory is put on disk, not moved somewhere clever.</b> Nine kit slots replace
 * it, and the thirty-six slots that were there are written to a file under the staff member's
 * uuid before anything is removed from them. That single decision is what makes this survive
 * the two ways a spectate used to strand somebody: a logout, and a server that stops between
 * one tick and the next. The file is the authority, the position and mode ride in the anticheat
 * store beside it, and the way back is one call - so neither a crash nor a restart can eat a
 * moderator's gear or leave them a ghost forever.
 *
 * <p><b>Every action is silent, and the target is told nothing.</b> A spectate that announces
 * itself is worthless: the whole reason to look at somebody is to catch them doing what they do
 * when nobody is looking. The only thing the target sees is nothing at all - the staff member is
 * vanished and in creative, so they do not appear, do not collide, and do not set off a single
 * trigger. That is also why {@code /ff anticheat spectate} says nothing in chat but a line to
 * the staff member themselves.
 */
public final class SpectateKit {
   /**
    * The kit item's type, carried in the stack's custom data.
    *
    * <p>Public because it is the item's identity rather than an implementation detail: the key is
    * written onto every stack the kit hands out and read back off it later, so anything deciding
    * whether a stack <i>is</i> a kit item - vanish, which has to leave the kit in the moderator's
    * hand while emptying everything else - has to be able to ask the same question the click
    * handler asks.
    */
   public static final String KIT_KEY = "ff_spectate_kit";
   /** The watched player's uuid, carried on every kit item so a restart keeps the pairing. */
   private static final String TARGET_KEY = "ff_spectate_target";

   public static final String CONFIRM = "confirm";
   public static final String FALSE_POSITIVE = "false";
   public static final String KICK = "kick";
   public static final String TIMEOUT = "timeout";
   public static final String UNBAN = "unban";
   public static final String RECORD = "record";
   public static final String PULL = "pull";
   public static final String WATCH = "watch";
   public static final String EXIT = "exit";

   /** How long a timeout handed out from the kit lasts. The same five minutes as the ladder. */
   public static final long KIT_TIMEOUT_MILLIS = AntiCheatStore.TIMEOUT_MILLIS;

   private SpectateKit() {
   }

   // ------------------------------------------------------------------- entering

   /**
    * Puts a moderator into the watching state: vanished, in creative, holding the kit, with
    * their own gear written to disk first.
    *
    * <p>Returns false when there is nothing sensible to do. The one refusal worth naming is
    * spectating yourself - it would stash your inventory into the kit's own file and then hand
    * it back to you as a kit.
    */
   public static boolean begin(ServerPlayer staff, ServerPlayer target) {
      if (staff == null || target == null || staff.getUUID().equals(target.getUUID())) {
         return false;
      }
      stash(staff);
      staff.setGameMode(GameType.CREATIVE);
      // The kit goes into the hands <i>before</i> the vanish, not after it. A vanish now empties the
      // hands for real - a held item is drawn by its own layer, and the empty packet cannot hide it
      // from the body's own third-person view - and the one stack it leaves alone is a kit item. Hand
      // the kit over first and the exemption applies to it; hand it over afterwards and the vanish
      // would have parked the kit in the wardrobe on its way past, leaving a moderator who cannot
      // click the thing the kit exists for.
      give(staff, target.getUUID(), target.getName().getString());
      VanishManager.vanish(staff);
      return true;
   }

   // -------------------------------------------------------------------- leaving

   /**
    * Takes the kit back out of the inventory and returns the gear it replaced.
    *
    * <p>Order matters and is the whole of the safety here. The kit is cleared first, so no kit
    * item can be handed to the player as gear. The wardrobe goes back second, because that is
    * where the armor and the held items are. The file is restored last, over the top: it holds
    * the same stacks the wardrobe does for those slots, so restoring after it cannot duplicate
    * anything, and it is the only copy of the thirty-six inventory slots if the server never
    * managed to save.
    */
   public static boolean end(ServerPlayer staff) {
      if (staff == null) {
         return false;
      }
      clear(staff);
      VanishManager.unvanish(staff);
      boolean restored = restore(staff);
      delete(staff);
      return restored;
   }

   /**
    * Hands back a stash left behind by a staff member who never got to leave properly.
    *
    * <p>Called on join. A moderator who crashed mid-watch, or whose server was killed, has
    * their gear waiting in the file and a spectate record still on the books: this puts the
    * record back where the player stood and gives them their inventory, in that order, so a
    * restart is invisible to them.
    */
   public static void onJoin(ServerPlayer staff) {
      if (staff == null) {
         return;
      }
      boolean wasWatching = AntiCheat.spectating(staff);
      if (wasWatching) {
         // This is the whole recovery: the same door a clean /ff anticheat unspectate uses,
         // which restores the recorded mode and place and then gives the gear back.
         AntiCheat.unspectate(staff);
         Chat.msg(staff, "&7You were still watching somebody when the server stopped - you are back where you were, with your items.");
         return;
      }
      if (hasStash(staff)) {
         // No record but a file: the kit was in their inventory when they left. Give the gear
         // back and say so, because silently replacing nine slots would be worse than the bug.
         clear(staff);
         restore(staff);
         delete(staff);
         Chat.msg(staff, "&7The moderation kit you were holding was put away and your items are back.");
      }
   }

   // ------------------------------------------------------------------ the items

   private static void give(ServerPlayer staff, UUID target, String targetName) {
      for (int slot = 0; slot < 9; slot++) {
         staff.getInventory().setItem(slot, stack(slot, target, targetName));
      }
      staff.containerMenu.broadcastChanges();
      staff.inventoryMenu.broadcastChanges();
   }

   private static ItemStack stack(int slot, UUID target, String targetName) {
      return switch (slot) {
         case 0 -> labelled(Items.IRON_SWORD, "&c&lConfirm hacking", CONFIRM, target,
            "&7Files your verdict against &f" + targetName + "&7 and", "&7opens the punishment screen.");
         case 1 -> labelled(Items.PAPER, "&a&lFalse positive", FALSE_POSITIVE, target,
            "&7Deletes the counts for every check that fired", "&7and records that the checks were wrong.");
         case 2 -> labelled(Items.IRON_DOOR, "&e&lKick", KICK, target,
            "&7Removes &f" + targetName + "&7 from the server once.");
         case 3 -> labelled(Items.CLOCK, "&6&lTimeout 5m", TIMEOUT, target,
            "&7Kicks &f" + targetName + "&7 and locks them out", "&7for five minutes.");
         case 4 -> labelled(Items.NAME_TAG, "&b&lLift the punishment", UNBAN, target,
            "&7Clears every active punishment on &f" + targetName + "&7.");
         case 5 -> labelled(Items.BOOK, "&f&lThe record", RECORD, target,
            "&7Every check that has fired on &f" + targetName + "&7,", "&7with the evidence beside it.");
         case 6 -> labelled(Items.ENDER_PEARL, "&d&lGo to them", PULL, target,
            "&7Puts you beside &f" + targetName + "&7 without", "&7leaving stealth.");
         case 7 -> labelled(Items.ENDER_EYE, "&5&lWatch closely", WATCH, target,
            "&7Every finding on &f" + targetName + "&7 reaches you", "&7as it happens, with the evidence.");
         default -> labelled(Items.BARRIER, "&4&lStop spectating", EXIT, target,
            "&7Right-click: the kit goes away, your items", "&7come back, and you return to survival.");
      };
   }

   /**
    * One kit item: an icon, a name, the action it performs, the player it is about, and lore.
    *
    * <p>Every kit item carries the target's uuid rather than reading it from a field, because
    * that is what makes the kit survive a restart: the item in a moderator's hand is the whole
    * pairing, and nothing has to be reconstructed on boot for a click to work.
    */
   private static ItemStack labelled(Item item, String name, String action, UUID target, String... lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.MAX_STACK_SIZE, 1);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(Chat.colorize(name)));
      List<Component> lines = new ArrayList<>();
      for (String line : lore) {
         lines.add(Component.literal(Chat.colorize(line)));
      }
      stack.set(DataComponents.LORE, new ItemLore(lines));
      CompoundTag tag = new CompoundTag();
      tag.putString(KIT_KEY, action);
      tag.putString(TARGET_KEY, target == null ? "" : target.toString());
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      return stack;
   }

   /** True for any stack this class handed out. */
   public static boolean isKitItem(ItemStack stack) {
      return action(stack) != null;
   }

   public static String action(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return null;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return null;
      }
      return data.copyTag().getString(KIT_KEY).orElse(null);
   }

   public static UUID targetOf(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return null;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return null;
      }
      String raw = data.copyTag().getString(TARGET_KEY).orElse(null);
      if (raw == null || raw.isBlank()) {
         return null;
      }
      try {
         return UUID.fromString(raw);
      } catch (Throwable t) {
         return null;
      }
   }

   // ------------------------------------------------------------------ the clicks

   /**
    * A right-click on a kit item. Returns true when the click was the kit's, which is what tells
    * the caller to swallow it - a moderator holding a sword is not holding a sword.
    */
   public static boolean use(ServerPlayer staff, ItemStack stack) {
      String action = action(stack);
      if (action == null) {
         return false;
      }
      UUID targetId = targetOf(stack);
      ServerPlayer target = find(staff, targetId);
      String what = action;
      switch (what) {
         case EXIT -> {
            AntiCheat.unspectate(staff);
            return true;
         }
         default -> {
         }
      }
      if (target == null) {
         Chat.msg(staff, "&7That player is not online any more - nothing to do, and the record is in &f/ff anticheat&7.");
         return true;
      }
      switch (what) {
         case CONFIRM -> {
            AntiCheatStore.review(target.getUUID(), target.getName().getString(), "*", false, staff.getName().getString(), "confirmed hacking while spectating");
            AntiCheatMenu.open(staff, true);
            Chat.msg(staff, "&cFiled against &f" + target.getName().getString() + "&c. The punishment screen is open.");
         }
         case FALSE_POSITIVE -> {
            for (String check : AntiCheat.CHECKS) {
               AntiCheatStore.review(
                  target.getUUID(), target.getName().getString(), check, true, staff.getName().getString(), "false positive from the kit"
               );
            }
            Chat.msg(staff, "&aEvery count on &f" + target.getName().getString() + "&a is cleared and the verdict is on the record.");
         }
         case KICK -> {
            // Through the one kick path, so the kit's button and the command behave
            // identically - including the re-entry window that makes a kick worth doing.
            AntiCheat.kick(target, "removed by a moderator", staff.getName().getString());
         }
         case TIMEOUT -> {
            long until = System.currentTimeMillis() + KIT_TIMEOUT_MILLIS;
            AntiCheatStore.punish(target, "timeout", "five minutes by " + staff.getName().getString(), staff.getName().getString(), until);
            AntiCheat.kickQuietly(target, "Timed out for five minutes by a moderator.");
         }
         case UNBAN -> Chat.msg(
            staff,
            AntiCheatStore.unban(target, staff.getName().getString())
               ? "&aEvery active punishment on &f" + target.getName().getString() + "&a is lifted."
               : "&7Nothing was in force on &f" + target.getName().getString() + "&7."
         );
         case RECORD -> AntiCheatMenu.open(staff, false);
         case PULL -> pull(staff, target);
         case WATCH -> {
            AntiCheat.watch(staff, target);
         }
         default -> {
         }
      }
      return true;
   }

   private static void pull(ServerPlayer staff, ServerPlayer target) {
      if (AntiCheat.teleportStaffTo(staff, target)) {
         Chat.msg(staff, "&7Beside &f" + target.getName().getString() + "&7 - you are still invisible to them.");
      } else {
         Chat.msg(staff, "&cThat move did not take - try again, or use &f/ff anticheat spectate&c.");
      }
   }

   private static ServerPlayer find(ServerPlayer staff, UUID id) {
      if (id == null) {
         return null;
      }
      MinecraftServer server = staff.level().getServer();
      return server == null ? null : server.getPlayerList().getPlayer(id);
   }

   /** Drops every kit item out of the inventory, in hand or not. */
   private static void clear(ServerPlayer staff) {
      for (int i = 0; i < staff.getInventory().getContainerSize(); i++) {
         if (isKitItem(staff.getInventory().getItem(i))) {
            staff.getInventory().setItem(i, ItemStack.EMPTY);
         }
      }
      staff.containerMenu.broadcastChanges();
      staff.inventoryMenu.broadcastChanges();
   }

   // ------------------------------------------------------------------ the stash

   private static Path stashFile(ServerPlayer staff) {
      MinecraftServer server = staff.level().getServer();
      Path dir = server == null
         ? Path.of("config", "fortuneandfavors", "spectate")
         : EconomyManager.getDataDir(server).resolve("spectate");
      return dir.resolve(staff.getUUID() + ".nbt");
   }

   private static boolean hasStash(ServerPlayer staff) {
      try {
         return staff != null && Files.exists(stashFile(staff));
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * Writes the whole inventory to disk before a single slot of it is touched.
    *
    * <p>Item stacks through their own codec rather than a hand-rolled NBT walk, so a component
    * added by a future version - or by another mod - survives the round trip. The registry is
    * the player's, which is what enchantments and painting variants resolve against.
    */
   private static void stash(ServerPlayer staff) {
      try {
         Path file = stashFile(staff);
         Files.createDirectories(file.getParent());
         RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, staff.registryAccess());
         ListTag list = new ListTag();
         for (int i = 0; i < staff.getInventory().getContainerSize(); i++) {
            ItemStack stack = staff.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) {
               continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", i);
            Tag encoded = ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result().orElse(null);
            if (encoded != null) {
               entry.put("Item", encoded);
               list.add(entry);
            }
         }
         CompoundTag root = new CompoundTag();
         root.put("Items", list);
         NbtIo.writeCompressed(root, file);
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error(
            "Fortune & Favors: could not stash {}'s inventory for spectating", staff.getName().getString(), t
         );
      }
   }

   private static boolean restore(ServerPlayer staff) {
      Path file = stashFile(staff);
      try {
         if (!Files.exists(file)) {
            return false;
         }
         CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
         ListTag list = root.getList("Items").orElse(null);
         if (list == null) {
            return false;
         }
         RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, staff.registryAccess());
         List<ItemStack> overflow = new ArrayList<>();
         for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i).orElse(null);
            if (entry == null) {
               continue;
            }
            Tag encoded = entry.get("Item");
            if (encoded == null) {
               continue;
            }
            ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(ops, encoded).result().orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) {
               continue;
            }
            int slot = entry.getInt("Slot").orElse(-1);
            if (slot >= 0 && slot < staff.getInventory().getContainerSize()) {
               staff.getInventory().setItem(slot, stack);
            } else {
               overflow.add(stack);
            }
         }
         for (ItemStack stack : overflow) {
            if (!staff.getInventory().add(stack) && staff.level() instanceof ServerLevel level) {
               level.addFreshEntity(new ItemEntity(level, staff.getX(), staff.getY() + 0.5, staff.getZ(), stack));
            }
         }
         staff.containerMenu.broadcastChanges();
         staff.inventoryMenu.broadcastChanges();
         return true;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error(
            "Fortune & Favors: could not restore {}'s inventory after spectating - the file is still at {}",
            staff.getName().getString(), file, t
         );
         return false;
      }
   }

   private static void delete(ServerPlayer staff) {
      try {
         Files.deleteIfExists(stashFile(staff));
      } catch (Throwable ignored) {
      }
   }
}
