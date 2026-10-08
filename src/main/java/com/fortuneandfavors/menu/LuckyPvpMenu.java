package com.fortuneandfavors.menu;

import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.level.ItemLike;

public class LuckyPvpMenu extends ChestMenu {
   private static final int SPIN_TICKS = 30;
   private static final int[] SLOTS = new int[]{11, 13, 15};
   private static final Set<LuckyPvpMenu> OPEN = ConcurrentHashMap.newKeySet();
   private static final Item[] ROLL_ICONS = new Item[]{
      Items.WOODEN_SWORD,
      Items.STONE_SWORD,
      Items.IRON_SWORD,
      Items.DIAMOND_SWORD,
      Items.NETHERITE_SWORD,
      Items.STICK,
      Items.BOW,
      Items.CROSSBOW,
      Items.IRON_CHESTPLATE,
      Items.DIAMOND_CHESTPLATE,
      Items.GOLDEN_APPLE,
      Items.ENDER_PEARL,
      Items.TOTEM_OF_UNDYING,
      Items.WARDEN_SPAWN_EGG,
      Items.LEATHER_HELMET,
      Items.LEATHER_BOOTS,
      Items.SHIELD,
      Items.IRON_AXE,
      Items.DIAMOND_AXE,
      Items.WIND_CHARGE,
      Items.FIRE_CHARGE,
      Items.COBWEB,
      Items.TNT,
      Items.LAVA_BUCKET,
      Items.WATER_BUCKET,
      Items.EXPERIENCE_BOTTLE,
      Items.ENCHANTED_GOLDEN_APPLE,
      Items.NETHERITE_HELMET,
      Items.NETHERITE_CHESTPLATE,
      Items.NETHERITE_LEGGINGS,
      Items.NETHERITE_BOOTS,
      Items.FISHING_ROD,
      Items.FIREWORK_ROCKET
   };
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final ItemStack weapon;
   private final ItemStack armor;
   private final ItemStack wildcard;
   private int spinTicks;
   private boolean revealed;
   private int closeAfter;

   private LuckyPvpMenu(int syncId, Inventory playerInventory, SimpleContainer container, ItemStack weapon, ItemStack armor, ItemStack wildcard) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.weapon = weapon;
      this.armor = armor;
      this.wildcard = wildcard;
      OPEN.add(this);
      this.rebuild();
   }

   public static void open(ServerPlayer player, ItemStack weapon, ItemStack armor, ItemStack wildcard) {
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new LuckyPvpMenu(syncId, inv, new SimpleContainer(27), weapon, armor, wildcard),
            Component.literal("§d§lLucky PvP §r§7- Your Items")
         )
      );
   }

   public static void tickAll(MinecraftServer server) {
      for (LuckyPvpMenu m : OPEN) {
         if (m.closeAfter > 0) {
            m.closeAfter--;
            if (m.closeAfter <= 0) {
               m.owner.closeContainer();
            }
         } else if (!m.revealed && m.owner.isAlive()) {
            m.spinTicks++;
            if (m.spinTicks % 3 == 0) {
               for (int slot : SLOTS) {
                  Item icon = ROLL_ICONS[(int)(Math.random() * ROLL_ICONS.length)];
                  m.container.setItem(slot, mystery(icon));
               }

               m.broadcastChanges();
               ServerLevel sl = m.owner.level();
               sl.playSound(
                  null, m.owner.getX(), m.owner.getY(), m.owner.getZ(), SoundEvents.NOTE_BLOCK_PLING, SoundSource.PLAYERS, 0.6F, 0.8F + m.spinTicks * 0.01F
               );
            }

            if (m.spinTicks >= 30) {
               m.reveal();
            }
         }
      }
   }

   private void rebuild() {
      this.container.clearContent();
      this.container.setItem(4, this.filler());

      for (int slot : SLOTS) {
         this.container.setItem(slot, mystery(Items.NETHER_STAR));
      }

      this.broadcastChanges();
   }

   private static ItemStack mystery(Item icon) {
      ItemStack s = new ItemStack(icon);
      s.set(DataComponents.CUSTOM_NAME, Component.literal("§d§l?"));
      return s;
   }

   private ItemStack filler() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.magenta());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l "));
      return stack;
   }

   private void reveal() {
      if (!this.revealed) {
         this.revealed = true;
         this.container.setItem(SLOTS[0], labeled(this.weapon, "§c§lWeapon"));
         this.container.setItem(SLOTS[1], labeled(this.armor, "§b§lArmor"));
         this.container.setItem(SLOTS[2], labeled(this.wildcard, "§a§lWildcard"));
         this.broadcastChanges();
         this.closeAfter = 35;
         ServerLevel sl = this.owner.level();
         sl.playSound(null, this.owner.getX(), this.owner.getY(), this.owner.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.2F);
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.TOTEM_OF_UNDYING, this.owner.getX(), this.owner.getY() + 1.2, this.owner.getZ(), 30, 0.5, 0.6, 0.5, 0.12);
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ENCHANT, this.owner.getX(), this.owner.getY() + 1.0, this.owner.getZ(), 16, 0.4, 0.5, 0.4, 0.1);
      }
   }

   private static ItemStack labeled(ItemStack base, String label) {
      ItemStack s = base.copy();
      List<Component> lore = s.get(DataComponents.LORE) != null ? ((ItemLore)s.get(DataComponents.LORE)).lines() : List.of();
      List<Component> lines = new ArrayList<>(lore);
      lines.add(0, Component.literal(label));
      s.set(DataComponents.LORE, new ItemLore(lines));
      return s;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         this.returnCarried(sp);
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }

   public void removed(Player player) {
      OPEN.remove(this);
      super.removed(player);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
