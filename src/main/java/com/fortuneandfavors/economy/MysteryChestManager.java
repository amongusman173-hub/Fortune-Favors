package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.List;
import java.util.Random;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

public final class MysteryChestManager {
   public static final int COMMON = 0;
   public static final int RARE = 1;
   public static final int EPIC = 2;
   public static final int LEGENDARY = 3;
   private static final String KEY_TAG = "ff_mystery_key";
   private static final Random RANDOM = new Random();

   private MysteryChestManager() {
   }

   public static String tierName(int tier) {
      return switch (tier) {
         case 0 -> "Common";
         case 1 -> "Rare";
         case 2 -> "Epic";
         default -> "Legendary";
      };
   }

   public static String tierColor(int tier) {
      return switch (tier) {
         case 0 -> "§7";
         case 1 -> "§b";
         case 2 -> "§5";
         default -> "§6";
      };
   }

   public static ItemStack mysteryKey(int tier) {
      ItemStack stack = new ItemStack(Items.TRIPWIRE_HOOK);
      ModItems.setType(stack, "mystery_key");
      // Each tier gets its own art - they all used to render as the same vanilla
      // tripwire hook, so a legendary key looked exactly like a common one.
      int clamped = Math.max(0, Math.min(3, tier));
      ModItems.setModel(stack, ModItems.MYSTERY_KEY_MODELS[clamped]);
      CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
      CompoundTag tag = data.copyTag();
      tag.putInt(KEY_TAG, Math.max(0, Math.min(3, tier)));
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(tierColor(tier) + "§l" + tierName(tier) + " Mystery Key"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to open a Mystery Chest."),
               Component.literal("§8Tier " + (tier + 1) + " of 4 - the higher the tier,"),
               Component.literal("§8the better the prizes.")
            )
         )
      );
      return stack;
   }

   public static int keyTier(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return -1;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return -1;
      }
      return data.copyTag().getInt(KEY_TAG).orElse(-1);
   }

   public static boolean isMysteryKey(ItemStack stack) {
      return keyTier(stack) >= 0;
   }

   public static String useKey(ServerPlayer player, ItemStack key) {
      int tier = keyTier(key);
      if (tier < 0) {
         return "That isn't a Mystery Key.";
      }
      if (!player.getAbilities().instabuild) {
         key.shrink(1);
      }
      ServerLevel level = player.level();
      long cash = switch (tier) {
         case 0 -> 500L + RANDOM.nextInt(2001);
         case 1 -> 2500L + RANDOM.nextInt(5001);
         case 2 -> 10000L + RANDOM.nextInt(20001);
         default -> 40000L + RANDOM.nextInt(60001);
      };
      boolean cashPrize = RANDOM.nextInt(100) < 70;
      String prizeName;
      if (cashPrize) {
         EconomyManager.addCash(player.getUUID(), cash);
         prizeName = "§a$" + cash;
      } else {
         ItemStack prize = randomItemPrize(tier);
         InventoryHelper.giveOrDrop(player, prize);
         prizeName = prize.getHoverName().getString();
      }
      if (tier >= 2) {
         CosmeticManager.Cosmetic cos = CosmeticManager.grantRandom(player, tier == 3);
         if (cos != null) {
            prizeName = prizeName + "§7 + §dcosmetic: §r" + cos.display();
         }
      }
      Advancements.grant(player, "keymaster");
      com.fortuneandfavors.net.FfVfx.particles(level, 
         ParticleTypes.END_ROD, player.getX(), player.getY() + 1.4, player.getZ(), 40, 0.6, 0.8, 0.6, 0.05
      );
      com.fortuneandfavors.net.FfVfx.particles(level, 
         ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1.4, player.getZ(), 16, 0.4, 0.6, 0.4, 0.05
      );
      SoundUtil.play(player, ModSounds.TRANSFER);
      Chat.raw(player, tierColor(tier) + "§l" + tierName(tier) + " Mystery Chest§r §7opened - you got " + prizeName + "§7!");
      return null;
   }

   private static ItemStack randomItemPrize(int tier) {
      if (tier >= 1 && RANDOM.nextInt(4) == 0) {
         return RuneManager.randomRune();
      }
      ItemStack stack = switch (tier) {
         case 0 -> new ItemStack(Items.DIAMOND, 1 + RANDOM.nextInt(3));
         case 1 -> new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1);
         case 2 -> ModItems.mysteryBox();
         default -> switch (RANDOM.nextInt(4)) {
            case 0 -> ModItems.slimeCore();
            case 1 -> ModItems.kingBone();
            case 2 -> ModItems.golemCore();
            default -> ModItems.mysteryBox();
         };
      };
      return stack;
   }

   public static ItemStack previewIcon(int tier) {
      ItemStack stack = switch (tier) {
         case 0 -> new ItemStack(Items.CHEST);
         case 1 -> new ItemStack(Items.BARREL);
         case 2 -> new ItemStack(Items.ENDER_CHEST);
         default -> new ItemStack(Items.SHULKER_BOX);
      };
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(tierColor(tier) + "§l" + tierName(tier) + " Mystery Chest"));
      return stack;
   }
}
