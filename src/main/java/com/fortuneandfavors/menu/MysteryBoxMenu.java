package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.menu.MysteryBoxMenu.Prize;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import com.fortuneandfavors.economy.EconomyManager;

public class MysteryBoxMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int PRIZE = 13;
   private static final int REVEAL = 22;
   private static final int SPIN_TICKS = 45;
   private static final Set<MysteryBoxMenu> OPEN = ConcurrentHashMap.newKeySet();
   private static final Random RANDOM = new Random();
   private static final List<ItemStack> PREVIEWS = List.of(
      preview(Items.GOLD_INGOT, "§eGold"),
      preview(Items.IRON_INGOT, "§7Iron"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.EMERALD, "§aEmeralds"),
      preview(Items.ENDER_PEARL, "§bEnder Pearls"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.DIAMOND_SWORD, "§bDiamond Sword"),
      preview(Items.BOW, "§6Enchanted Bow"),
      preview(Items.DIAMOND_PICKAXE, "§bDiamond Pickaxe"),
      preview(Items.DIAMOND_BLOCK, "§bDiamond Block"),
      preview(Items.ENCHANTED_GOLDEN_APPLE, "§dGod Apple"),
      preview(Items.BONE, "§5§lWither Staff"),
      preview(Items.NETHERITE_SWORD, "§5§lWither Blade"),
      preview(Items.NETHERITE_HELMET, "§5§lWither Crown")
   );
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private boolean revealed = false;
   private boolean spinning = false;
   private int spinIndex = 0;
   private int spinTicks = 0;
   private Prize pendingPrize = null;
   private ItemStack result = ItemStack.EMPTY;

   public MysteryBoxMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private MysteryBoxMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      OPEN.add(this);
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new MysteryBoxMenu(syncId, inv), Component.literal("§5§lMystery Box")));
   }

   /** Advances every open mystery box's spin animation and resolves it. */
   public static void tickAll(MinecraftServer server) {
      for (MysteryBoxMenu m : OPEN) {
         if (!m.revealed && m.spinning && m.owner.isAlive()) {
            m.spinTicks++;
            // The prize is rolled when the spin starts, and the reel snaps onto
            // it for the last few ticks - so it always lands exactly on what
            // you actually won, even for cash prizes.
            if (m.spinTicks >= SPIN_TICKS - 4) {
               if (m.pendingPrize != null) {
                  m.container.setItem(PRIZE, m.reelStack(m.pendingPrize));
                  m.broadcastChanges();
               }
            } else {
               int speed = m.spinTicks < 28 ? 1 : (m.spinTicks < 38 ? 2 : 3);
               if (m.spinTicks % speed == 0) {
                  m.spinIndex++;
                  m.container.setItem(PRIZE, PREVIEWS.get(m.spinIndex % PREVIEWS.size()).copy());
                  m.broadcastChanges();
                  float t = m.spinTicks / (float)SPIN_TICKS;
                  SoundUtil.play(m.owner, ModSounds.LOOT_TICK, 0.7F + t * 1.1F);
               }
            }

            if (m.owner.level() instanceof ServerLevel sl) {
               double x = m.owner.getX();
               double y = m.owner.getY() + 1.0;
               double z = m.owner.getZ();
               int n = 10;

               for (int i = 0; i < n; i++) {
                  double a = i / (double)n * Math.PI * 2.0 + m.spinTicks * 0.18;
                  float hue = (float)((i / (double)n + m.spinTicks * 0.005) % 1.0);
                  int color = Color.HSBtoRGB(hue, 0.85F, 1.0F) & 16777215;
                  sl.sendParticles(
                     new DustParticleOptions(color, 0.9F),
                     x + Math.cos(a) * 1.1,
                     y + Math.sin(m.spinTicks * 0.15) * 0.4,
                     z + Math.sin(a) * 1.1,
                     1,
                     0.0,
                     0.0,
                     0.0,
                     0.0
                  );
               }

               sl.sendParticles(ParticleTypes.ENCHANT, x, y + 0.4, z, 3, 0.5, 0.7, 0.5, 0.06);
               sl.sendParticles(ParticleTypes.END_ROD, x, y + 0.2, z, 1, 0.3, 0.4, 0.3, 0.02);
            }

            if (m.spinTicks >= SPIN_TICKS) {
               m.finishSpin(m.owner);
            }
         }
      }
   }

   private void rebuild() {
      this.container.clearContent();
      Item black = (Item)Items.STAINED_GLASS_PANE.black();
      Item purple = (Item)Items.STAINED_GLASS_PANE.purple();

      for (int g = 0; g < 27; g++) {
         if (g == INFO || g == PRIZE || g == REVEAL) {
            continue;
         }

         boolean border = g < 9 || g >= 18 || g % 9 == 0 || g % 9 == 8;
         this.container.setItem(g, this.frame(border ? black : purple));
      }

      this.container.setItem(INFO, this.infoCard());
      if (this.revealed) {
         this.container.setItem(PRIZE, this.result.copy());
         this.container.setItem(REVEAL, this.named(Items.BARRIER, "§7Opened"));
      } else if (this.spinning) {
         this.container.setItem(PRIZE, PREVIEWS.get(this.spinIndex % PREVIEWS.size()).copy());
         this.container.setItem(REVEAL, this.named(Items.FIREWORK_ROCKET, "§d§lSPINNING..."));
      } else {
         ItemStack idle = new ItemStack(Items.BARREL);
         idle.set(DataComponents.CUSTOM_NAME, Component.literal("§5§l? ? ?"));
         idle.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Click §a§lSPIN§7 below to see"), Component.literal("§7what's inside the box!"))));
         this.container.setItem(PRIZE, idle);
         this.container.setItem(REVEAL, this.named(Items.TRIDENT, "§a§lSPIN"));
      }
   }

   private ItemStack infoCard() {
      ItemStack stack = new ItemStack(Items.BARREL);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lMystery Box"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Won by leveling up a skill."),
               Component.literal("§7One box is consumed per reveal - a"),
               Component.literal("§5§llegendary§7 keeps the box!"),
               Component.literal(""),
               Component.literal("§7What's inside:"),
               Component.literal("§5§lLegendary §7(2%) - Wither gear or a God Apple"),
               Component.literal("§6Rare §7- enchanted gear, diamond blocks, big cash"),
               Component.literal("§7Common §7- gold, iron, pearls, small cash"),
               Component.literal(""),
               Component.literal("§8Prizes go straight to your inventory/balance.")
            )
         )
      );
      return stack;
   }

   private ItemStack frame(Item item) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      return stack;
   }

   private ItemStack named(Item item, String name) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return stack;
   }

   /** The stack the reel shows when it lands - the actual prize item (or a gold
    *  ingot standing in for cash), named with the prize label. */
   private ItemStack reelStack(Prize prize) {
      ItemStack s = prize.stack.isEmpty() ? new ItemStack(Items.GOLD_INGOT) : prize.stack.copy();
      s.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l" + prize.label));
      return s;
   }

   private static ItemStack preview(Item item, String name) {
      ItemStack stack = new ItemStack(item);
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
         if (slotId == REVEAL) {
            this.startSpin(sp);
         } else {
            this.returnCarried(sp);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private void startSpin(ServerPlayer player) {
      if (this.revealed) {
         SoundUtil.play(player, ModSounds.DENY);
         Chat.msg(player, "&cThis box is already open.");         } else if (!this.spinning) {
         if (!this.hasBox(player)) {
            SoundUtil.play(player, ModSounds.DENY);
            Chat.msg(player, "&cYou don't have a mystery box anymore - close this window.");
         } else {
            // Roll up front so the reel can land on the actual prize; the box
            // is still only consumed at the end of the spin.
            this.pendingPrize = roll(player);
            this.spinning = true;
            this.spinTicks = 0;
            this.rebuild();
            this.broadcastChanges();
            SoundUtil.play(player, ModSounds.MYSTERY);
         }
      }
   }

   /** Rolls, consumes the box (unless legendary) and pays out - only reached at
    *  the end of the spin, so closing the window mid-spin never spends the box. */
   private void finishSpin(ServerPlayer player) {
      this.spinning = false;
      if (!this.hasBox(player)) {
         SoundUtil.play(player, ModSounds.DENY);
         Chat.msg(player, "&cYou don't have a mystery box anymore - close this window.");
         this.rebuild();
         this.broadcastChanges();
         return;
      }

      Prize prize = this.pendingPrize != null ? this.pendingPrize : roll(player);
      this.pendingPrize = null;
      if (prize.legendary) {
         Chat.raw(player, "&d&lJACKPOT!&r &7Your mystery box is kept - a legendary doesn't spend it!");
      } else {
         this.consumeBox(player);
      }

      ItemStack display = prize.grant(player);
      display.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l" + prize.label));
      ItemLore lore = (ItemLore)display.get(DataComponents.LORE);
      List<Component> lines = lore != null ? new ArrayList<>(lore.lines()) : new ArrayList<>();
      lines.add(Component.literal("§8Your prize from the Mystery Box."));
      display.set(DataComponents.LORE, new ItemLore(lines));
      this.result = display;
      this.revealed = true;
      this.rebuild();
      this.broadcastChanges();
      this.celebrate(player, prize.legendary);
      Chat.raw(player, "&5&lMystery Box:&r &7You got &f" + prize.label + "&7!");
      this.returnCarried(player);
   }

   private void celebrate(ServerPlayer player, boolean legendary) {
      if (player.level() instanceof ServerLevel sl) {
         double x = player.getX();
         double y = player.getY() + 1.5;
         double z = player.getZ();
         sl.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, legendary ? 16777215 : 16769216), x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
         int ring = legendary ? 36 : 24;

         for (int i = 0; i < ring; i++) {
            double a = i / (double)ring * Math.PI * 2.0;
            float hue = i / (float)ring;
            int color = Color.HSBtoRGB(hue, 0.9F, 1.0F) & 16777215;
            sl.sendParticles(
               new DustParticleOptions(color, legendary ? 1.5F : 1.1F),
               x + Math.cos(a) * (legendary ? 2.0 : 1.5),
               y + Math.sin(a * 2.0) * 0.5,
               z + Math.sin(a) * (legendary ? 2.0 : 1.5),
               1,
               0.12,
               0.12,
               0.12,
               0.0
            );
         }

         sl.sendParticles(ParticleTypes.ENCHANT, x, y, z, legendary ? 90 : 60, 1.1, 1.1, 1.1, 0.12);
         sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, legendary ? 40 : 28, 0.75, 0.75, 0.75, 0.04);
         sl.sendParticles(ParticleTypes.END_ROD, x, y, z, legendary ? 36 : 24, 0.65, 0.95, 0.65, 0.06);
         sl.sendParticles(ParticleTypes.FIREWORK, x, y, z, legendary ? 18 : 12, 0.5, 0.8, 0.5, 0.08);
      }

      if (legendary) {
         float[] chord = new float[]{0.4F, 0.5F, 0.63F, 0.75F, 1.0F, 1.26F, 1.6F};

         for (float p : chord) {
            SoundUtil.play(player, ModSounds.LOOT_TICK, p);
         }

         SoundUtil.play(player, ModSounds.LOOT_REVEAL, 1.5F, 1.2F);
      } else {
         for (float pitch : new float[]{0.6F, 0.75F, 1.0F, 1.26F}) {
            SoundUtil.play(player, ModSounds.LOOT_TICK, pitch);
         }

         SoundUtil.play(player, ModSounds.LOOT_REVEAL);
      }
   }

   private boolean hasBox(ServerPlayer player) {
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         if (ModItems.isMysteryBox(player.getInventory().getItem(i))) {
            return true;
         }
      }

      return false;
   }

   private void consumeBox(ServerPlayer player) {
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (ModItems.isMysteryBox(stack)) {
            stack.shrink(1);
            return;
         }
      }
   }

   public void removed(Player player) {
      OPEN.remove(this);
      super.removed(player);
   }

   private static Prize roll(ServerPlayer player) {
      int roll = RANDOM.nextInt(100);
      if (roll < 30) {
         return new Prize("§a$" + (100 + RANDOM.nextInt(400)), ItemStack.EMPTY, 100 + RANDOM.nextInt(400), false);
      }

      if (roll < 45) {
         return new Prize("§a$" + (500 + RANDOM.nextInt(1500)), ItemStack.EMPTY, 500 + RANDOM.nextInt(1500), false);
      }

      if (roll < 55) {
         return new Prize("§b8 Diamonds", stack(Items.DIAMOND, 8), 0L, false);
      }

      if (roll < 63) {
         ItemStack s = stack(Items.EMERALD, 4);
         return new Prize("§a4 Emeralds & 16 Gold", s, 0L, false);
      }

      if (roll < 71) {
         ItemStack s = stack(Items.DIAMOND_PICKAXE, 1);
         enchant(player, s, Enchantments.EFFICIENCY, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Pickaxe", s, 0L, false);
      }

      if (roll < 77) {
         return new Prize("§a24 Ender Pearls", stack(Items.ENDER_PEARL, 24), 0L, false);
      }

      if (roll < 82) {
         ItemStack s = stack(Items.BOW, 1);
         enchant(player, s, Enchantments.POWER, 4);
         enchant(player, s, Enchantments.INFINITY, 1);
         return new Prize("§6Enchanted Bow", s, 0L, false);
      }

      if (roll < 86) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L, false);
      }

      if (roll < 90) {
         return new Prize("§a16 Ender Pearls", stack(Items.ENDER_PEARL, 16), 0L, false);
      }

      if (roll < 93) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L, false);
      }

      if (roll < 96) {
         return new Prize("§bDiamond Block", stack(Items.DIAMOND_BLOCK, 1), 0L, false);
      }

      if (roll < 98) {
         return new Prize("§6§l$" + (5000 + RANDOM.nextInt(5000)), ItemStack.EMPTY, 5000 + RANDOM.nextInt(5000), false);
      }

      if (roll < 99) {
         int pick = RANDOM.nextInt(3);

         return switch (pick) {
            case 0 -> new Prize("§5§lWither Skeleton Staff", ModItems.witherStaff(), 0L, true);
            case 1 -> new Prize("§5§lWither Skeleton Blade", ModItems.witherBlade(), 0L, true);
            default -> new Prize("§5§lWither Skeleton Crown", ModItems.witherCrown(), 0L, true);
         };
      } else {
         return new Prize("§d§lEnchanted Golden Apple", stack(Items.ENCHANTED_GOLDEN_APPLE, 1), 0L, true);
      }
   }

   private static ItemStack stack(Item item, int count) {
      return new ItemStack(item, count);
   }

   private static void enchant(ServerPlayer player, ItemStack stack, ResourceKey<Enchantment> key, int level) {
      try {
         Registry<Enchantment> reg = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
         stack.enchant(reg.getOrThrow(key), level);
      } catch (Exception var5) {
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }


    record Prize(String label, ItemStack stack, long cash, boolean legendary) {
       ItemStack grant(ServerPlayer player) {
          if (this.cash > 0L) {
             EconomyManager.addCash(player.getUUID(), this.cash);
             ItemStack show = new ItemStack(Items.GOLD_INGOT);
             show.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l$" + this.cash));
             show.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Added to your balance."))));
             return show;
          } else {
             ItemStack give = this.stack.copy();
             InventoryHelper.giveOrDrop(player, give);
             return this.stack.copy();
          }
       }
    }
}
