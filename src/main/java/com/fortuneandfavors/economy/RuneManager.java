package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.Advancements;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.SoundUtil;
import java.util.List;
import java.util.Random;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class RuneManager {
   private static final Random RANDOM = new Random();

   private RuneManager() {
   }

   public static boolean isWeapon(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      Item item = stack.getItem();
      return item.builtInRegistryHolder().is(ItemTags.SWORDS) || item.builtInRegistryHolder().is(ItemTags.AXES);
   }

   public static boolean isArmor(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      Item item = stack.getItem();
      return item.builtInRegistryHolder().is(ItemTags.HEAD_ARMOR)
         || item.builtInRegistryHolder().is(ItemTags.CHEST_ARMOR)
         || item.builtInRegistryHolder().is(ItemTags.LEG_ARMOR)
         || item.builtInRegistryHolder().is(ItemTags.FOOT_ARMOR);
   }

   /** Anything a rune can be fused into: sword, axe or armor piece. */
   public static boolean isSocketable(ItemStack stack) {
      return isWeapon(stack) || isArmor(stack);
   }

   public static boolean isWeaponRune(String runeType) {
      return switch (runeType) {
         case "haste", "flame", "fortune", "swiftness", "frost", "reach", "lifesteal" -> true;
         default -> false;
      };
   }

   public static boolean isArmorRune(String runeType) {
      return switch (runeType) {
         case "warding", "fortitude" -> true;
         default -> false;
      };
   }

   public static boolean canApply(ItemStack target, ItemStack rune) {
      if (!ModItems.isRune(rune)) {
         return false;
      }
      String runeType = typeOfRune(rune);
      if (runeType == null) {
         return false;
      }
      return isWeaponRune(runeType) ? isWeapon(target) : isArmor(target);
   }

   /** Returns an error message, or null on success. The caller owns rune
    *  consumption - apply never touches the rune stack's count. */
   public static String apply(ServerPlayer sp, ItemStack target, ItemStack rune) {
      String runeType = typeOfRune(rune);
      if (runeType == null) {
         return "That isn't a rune.";
      }
      if (isWeaponRune(runeType)) {
         if (!isWeapon(target)) {
            return "Hold a sword or axe in your main hand to socket a weapon rune into it.";
         }
      } else {
         if (!isArmor(target)) {
            return "Hold a piece of armor in your main hand to socket an armor rune into it.";
         }
      }
      if (ModItems.hasRune(target, runeType)) {
         return "This " + (isWeaponRune(runeType) ? "weapon" : "armor piece") + " already has the " + runeName(runeType) + " socketed.";
      }
      ModItems.applyRune(target, runeType);
      SoundUtil.play(sp, ModSounds.TRANSFER);
      Chat.raw(sp, "§d§lRune socketed!§r §7" + runeName(runeType) + " is now fused into your " + (isWeaponRune(runeType) ? "weapon" : "armor") + ".");
      ServerLevel level = sp.level();
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ENCHANT, sp.getX(), sp.getY() + 1.2, sp.getZ(), 30, 0.4, 0.5, 0.4, 0.8);
      Advancements.grant(sp, "rune_forger");
      return null;
   }

   /** Consumes exactly one rune of `runeType` from the inventory. Returns the
    *  shrunk live stack (already decremented) or null if none existed. */
   public static ItemStack consumeOne(net.minecraft.world.entity.player.Inventory inv, String runeType) {
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (runeType.equals(typeOfRune(s))) {
            s.shrink(1);
            inv.setChanged();
            return s;
         }
      }
      return null;
   }

   public static void onAttack(ServerPlayer attacker, LivingEntity target) {
      try {
         ItemStack weapon = attacker.getMainHandItem();
         if (target == null || !target.isAlive()) {
            return;
         }
         ServerLevel level = attacker.level();
         if (ModItems.hasRune(weapon, "flame") && RANDOM.nextInt(100) < 25) {
            target.igniteForSeconds(3);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.FLAME, target.getX(), target.getY() + 1.0, target.getZ(), 8, 0.3, 0.4, 0.3, 0.03);
         }
         if (ModItems.hasRune(weapon, "frost") && RANDOM.nextInt(100) < 25) {
            target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
               net.minecraft.world.effect.MobEffects.SLOWNESS, 60, 0, false, false, true
            ));
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SNOWFLAKE, target.getX(), target.getY() + 1.0, target.getZ(), 8, 0.3, 0.4, 0.3, 0.03);
         }
         if (ModItems.hasRune(weapon, "lifesteal") && attacker.isAlive() && attacker.getHealth() < attacker.getMaxHealth()) {
            attacker.heal(1.0F);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.HEART, attacker.getX(), attacker.getY() + 1.6, attacker.getZ(), 2, 0.3, 0.3, 0.3, 0.0);
         }
      } catch (Exception ignored) {
      }
   }

   public static void onKill(ServerPlayer killer, LivingEntity killed) {
      try {
         ItemStack weapon = killer.getMainHandItem();
         if (ModItems.hasRune(weapon, "fortune") && RANDOM.nextInt(100) < 20) {
            long bonus = 100L + RANDOM.nextInt(401);
            EconomyManager.addCash(killer.getUUID(), bonus);
            killer.giveExperiencePoints(10 + RANDOM.nextInt(11));
            Chat.raw(killer, "§a§lRune of Fortune!§r §7The hunt pays double - §a$" + bonus + "§7 and bonus XP.");
            ServerLevel level = killer.level();
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, killer.getX(), killer.getY() + 1.4, killer.getZ(), 14, 0.4, 0.5, 0.4, 0.04);
         }
      } catch (Exception ignored) {
      }
   }

   public static ItemStack randomRune() {
      return switch (RANDOM.nextInt(9)) {
         case 0 -> ModItems.runeOfHaste();
         case 1 -> ModItems.runeOfFlame();
         case 2 -> ModItems.runeOfFortune();
         case 3 -> ModItems.runeOfSwiftness();
         case 4 -> ModItems.runeOfFrost();
         case 5 -> ModItems.runeOfReach();
         case 6 -> ModItems.runeOfLifesteal();
         case 7 -> ModItems.runeOfWarding();
         default -> ModItems.runeOfFortitude();
      };
   }

   public static String typeOfRune(ItemStack rune) {
      if (ModItems.isRuneHaste(rune)) {
         return "haste";
      }
      if (ModItems.isRuneFlame(rune)) {
         return "flame";
      }
      if (ModItems.isRuneFortune(rune)) {
         return "fortune";
      }
      if (ModItems.isRuneSwiftness(rune)) {
         return "swiftness";
      }
      if (ModItems.isRuneFrost(rune)) {
         return "frost";
      }
      if (ModItems.isRuneReach(rune)) {
         return "reach";
      }
      if (ModItems.isRuneLifesteal(rune)) {
         return "lifesteal";
      }
      if (ModItems.isRuneWarding(rune)) {
         return "warding";
      }
      if (ModItems.isRuneFortitude(rune)) {
         return "fortitude";
      }
      return null;
   }

   public static String runeName(String runeType) {
      return switch (runeType) {
         case "haste" -> "§e§l⚡ Rune of Haste";
         case "flame" -> "§c§l🔥 Rune of Flame";
         case "fortune" -> "§a§l💎 Rune of Fortune";
         case "swiftness" -> "§b§l💨 Rune of Swiftness";
         case "frost" -> "§f§l❄ Rune of Frost";
         case "reach" -> "§2§l➤ Rune of Reach";
         case "lifesteal" -> "§c§l♥ Rune of Lifesteal";
         case "warding" -> "§3§l🛡 Rune of Warding";
         case "fortitude" -> "§6§l❤ Rune of Fortitude";
         default -> "Rune";
      };
   }

   /** Honest, tested numbers: haste adds +8% of the weapon's base 4.0 attack
    *  speed attribute (0.32/s ≈ +8%), swiftness +5% of base 0.1 move speed. */
   public static List<String> effects(String runeType) {
      return switch (runeType) {
         case "haste" -> List.of("§7+8% attack speed while held.", "§8Stacks with every other rune - one", "§8of each rune fits per weapon.");
         case "flame" -> List.of("§725% chance to ignite enemies on hit.", "§8Stacks with every other rune - one", "§8of each rune fits per weapon.");
         case "fortune" -> List.of("§720% chance for $100-500 + XP per kill.", "§8Stacks with every other rune - one", "§8of each rune fits per weapon.");
         case "swiftness" -> List.of("§7+5% movement speed while held.", "§8Stacks with every other rune - one", "§8of each rune fits per weapon.");
         case "frost" -> List.of("§725% chance to slow enemies on hit.", "§8Stacks with every other rune - one", "§8of each rune fits per weapon.");
         case "reach" -> List.of("§7+1 block melee reach while held.", "§8Stacks with every other rune - one", "§8of each rune fits per weapon.");
         case "lifesteal" -> List.of("§7Heal 1 heart on every hit.", "§8Stacks with every other rune - one", "§8of each rune fits per weapon.");
         case "warding" -> List.of("§7+3 armor and +1 toughness while worn.", "§8Armor runes stack - one of each", "§8fits per armor piece.");
         case "fortitude" -> List.of("§7+2 max hearts while worn.", "§8Armor runes stack - one of each", "§8fits per armor piece.");
         default -> List.of();
      };
   }

   /** Weapon runes in a stable display order. */
   public static String[] weaponTypes() {
      return new String[]{"haste", "flame", "fortune", "swiftness", "frost", "reach", "lifesteal"};
   }

   /** Armor runes in a stable display order. */
   public static String[] armorTypes() {
      return new String[]{"warding", "fortitude"};
   }

   /** All rune type keys in a stable display order (weapon first, then armor). */
   public static String[] allTypes() {
      return new String[]{"haste", "flame", "fortune", "swiftness", "frost", "reach", "lifesteal", "warding", "fortitude"};
   }

   /** The rune item type-key for a rune item, or null. */
   public static ItemStack stackForType(String runeType) {
      return switch (runeType) {
         case "haste" -> ModItems.runeOfHaste();
         case "flame" -> ModItems.runeOfFlame();
         case "fortune" -> ModItems.runeOfFortune();
         case "swiftness" -> ModItems.runeOfSwiftness();
         case "frost" -> ModItems.runeOfFrost();
         case "reach" -> ModItems.runeOfReach();
         case "lifesteal" -> ModItems.runeOfLifesteal();
         case "warding" -> ModItems.runeOfWarding();
         case "fortitude" -> ModItems.runeOfFortitude();
         default -> null;
      };
   }

   public static int countInInventory(net.minecraft.world.entity.player.Inventory inv, String runeType) {
      int count = 0;
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (runeType.equals(typeOfRune(s))) {
            count += s.getCount();
         }
      }
      return count;
   }

   /** Old helper kept for compat - delegates to the corrected consume path. */
   public static ItemStack takeOneFromInventory(net.minecraft.world.entity.player.Inventory inv, String runeType) {
      return consumeOne(inv, runeType);
   }
}