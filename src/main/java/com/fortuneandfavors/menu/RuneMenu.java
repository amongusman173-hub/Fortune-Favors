package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.RuneManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
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

public class RuneMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int TARGET = 13;
   private static final int WEAPON_RUNES_START = 19;
   private static final int ARMOR_RUNES_START = 28;
   private static final int CLOSE = 34;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public RuneMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(45));
   }

   private RuneMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x5, syncId, playerInventory, container, 5);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new RuneMenu(syncId, inv), Component.literal("§d§lRunes")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 5, Items.STAINED_GLASS_PANE.purple());
      ItemStack info = new ItemStack(Items.ENCHANTING_TABLE);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lRune Forging"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Fuse runes into the §fsword, axe or armor§7 you're holding."),
               Component.literal("§7Weapons take one of EVERY weapon rune - all seven"),
               Component.literal("§7at once, each stacking its effect. Armor pieces take"),
               Component.literal("§7one of every armor rune - both at once."),
               Component.literal("§7Click a rune below to fuse it in - one is consumed."),
               Component.literal("§7Also fusable in the §6Item Forge§7 (item + rune),"),
               Component.literal("§7or hold rune + item in both hands and right-click."),
               Component.literal("§7Runes drop from Mystery Chests, raids and bosses.")
            )
         )
      );
      this.container.setItem(INFO, info);
      ItemStack held = this.owner.getMainHandItem();
      ItemStack target = held.copy();
      boolean socketable = RuneManager.isSocketable(held);
      target.set(
         DataComponents.CUSTOM_NAME,
         Component.literal(socketable ? "§fHeld item" : "§8No sword, axe or armor held")
      );
      List<Component> targetLore = new ArrayList<>();
      if (socketable) {
         targetLore.add(Component.literal("§7" + held.getHoverName().getString()));
         boolean any = false;
         for (String t : RuneManager.allTypes()) {
            if (ModItems.hasRune(held, t)) {
               targetLore.add(Component.literal("  §d✓ " + RuneManager.runeName(t)));
               any = true;
            }
         }
         if (!any) {
            targetLore.add(Component.literal("§8No runes socketed yet."));
         }
      } else {
         targetLore.add(Component.literal("§7Hold a sword, axe or armor piece in your"));
         targetLore.add(Component.literal("§7main hand, then open this menu again."));
      }
      target.set(DataComponents.LORE, new ItemLore(targetLore));
      this.container.setItem(TARGET, target);

      String[] weaponTypes = RuneManager.weaponTypes();
      for (int i = 0; i < weaponTypes.length; i++) {
         this.container.setItem(WEAPON_RUNES_START + i, this.runeCard(weaponTypes[i], held, true));
      }
      String[] armorTypes = RuneManager.armorTypes();
      for (int i = 0; i < armorTypes.length; i++) {
         this.container.setItem(ARMOR_RUNES_START + i, this.runeCard(armorTypes[i], held, false));
      }
      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   private ItemStack runeCard(String type, ItemStack held, boolean weaponRune) {
      ItemStack stack = RuneManager.stackForType(type);
      boolean socketed = RuneManager.isSocketable(held) && com.fortuneandfavors.ModItems.hasRune(held, type);
      int count = RuneManager.countInInventory(this.owner.getInventory(), type);
      List<Component> lore = new ArrayList<>();
      for (String line : RuneManager.effects(type)) {
         lore.add(Component.literal(line));
      }
      lore.add(Component.literal(socketed ? "§a✓ Socketed in your item" : count > 0 ? "§7You have §f" + count + "§7 - click to socket" : "§8You don't have this rune yet"));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      if (socketed) {
         stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      }
      return stack;
   }

   private ItemStack named(ItemStack stack, String name) {
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
         // Weapon rune row
         String[] weaponTypes = RuneManager.weaponTypes();
         for (int i = 0; i < weaponTypes.length; i++) {
            if (slotId == WEAPON_RUNES_START + i) {
               this.socket(sp, weaponTypes[i], true);
               return;
            }
         }
         // Armor rune row
         String[] armorTypes = RuneManager.armorTypes();
         for (int i = 0; i < armorTypes.length; i++) {
            if (slotId == ARMOR_RUNES_START + i) {
               this.socket(sp, armorTypes[i], false);
               return;
            }
         }
         this.returnCarried(sp);
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private void socket(ServerPlayer sp, String type, boolean weaponRune) {
      this.returnCarried(sp);
      ItemStack held = sp.getMainHandItem();
      boolean fits = weaponRune ? RuneManager.isWeapon(held) : RuneManager.isArmor(held);
      if (!fits) {
         Chat.msg(sp, weaponRune ? "&cHold a sword or axe in your main hand to socket weapon runes." : "&cHold a piece of armor in your main hand to socket armor runes.");
         SoundUtil.play(sp, ModSounds.DENY);
      } else if (com.fortuneandfavors.ModItems.hasRune(held, type)) {
         Chat.msg(sp, "&cYour item already has the " + RuneManager.runeName(type) + "§c socketed.");
         SoundUtil.play(sp, ModSounds.DENY);
      } else {
         // Take a COPY to validate first, so a failed socket never
         // consumes the rune - and apply() no longer shrinks it, so
         // socketing one rune can never eat a second one.
         ItemStack runeCheck = RuneManager.stackForType(type);
         String err = RuneManager.apply(sp, held, runeCheck);
         if (err != null) {
            Chat.msg(sp, "&c" + err);
            SoundUtil.play(sp, ModSounds.DENY);
         } else if (RuneManager.consumeOne(sp.getInventory(), type) == null) {
            // raced away somehow - undo the socket so nothing is free
            com.fortuneandfavors.ModItems.removeRune(held, type);
            Chat.msg(sp, "&cThe rune vanished from your inventory.");
            SoundUtil.play(sp, ModSounds.DENY);
         } else {
            com.fortuneandfavors.ModEvents.grantAllRunes(sp, held);
         }
      }
      this.rebuild();
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}