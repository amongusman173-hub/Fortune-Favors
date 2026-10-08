package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.SoundUtil;
import com.fortuneandfavors.ModSounds;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/**
 * The Repair Station: the anvil's job, priced instead of enchanted.
 *
 * <p>An anvil asks for XP and levels, and levels are a resource a mob farm prints. This asks for
 * money instead, which is the resource the whole economy competes for - and it prices the repair
 * off the tool standing in front of it rather than off a flat fee, so mending a netherite pickaxe
 * with four enchantments on it is a decision and not a formality. Nothing is consumed but cash:
 * no levels, no repair materials, and the tool keeps its name, its enchantments and its anvil
 * history.
 *
 * <p>The last part is what makes it a sink rather than a shortcut. Every repair a player buys makes
 * their next one dearer, up to a ceiling - so a player who mends their gear at the station every
 * single fight pays several times what one who waits until it matters pays.
 */
public final class RepairStation {
   /** The floor under any repair - a price for the visit, not the work. */
   public static final long BASE = 400L;
   /** Added per point of the tool's own maximum durability. */
   public static final long PER_DURABILITY = 14L;
   /** Multiplied in for each enchantment the tool is carrying. */
   public static final double PER_ENCHANT = 0.22;
   /** How much dearer each repair a player has already bought this session makes the next one. */
   public static final double RISING_STEP = 0.08;
   /** ...and where that stops rising: double the list price, and no further. */
   public static final double RISING_CAP = 2.0;

   /** Repairs each player has already bought this session - the point of the rising price. */
   private static final Map<UUID, Integer> bought = new HashMap<>();

   private RepairStation() {
   }

   /** What mending this tool would cost right now, or 0 when there is nothing to mend. */
   public static long priceFor(ItemStack stack, UUID player) {
      if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) {
         return 0L;
      }
      int damage = stack.getDamageValue();
      if (damage <= 0) {
         return 0L;
      }
      double wear = damage / (double)Math.max(1, stack.getMaxDamage());
      long list = BASE + stack.getMaxDamage() * PER_DURABILITY;
      double withEnchants = list * (1.0 + PER_ENCHANT * enchantCount(stack));
      double rising = Math.min(RISING_CAP, 1.0 + RISING_STEP * boughtCount(player));
      return Math.max(BASE, Math.round(withEnchants * wear * rising));
   }

   /** The line that explains the price, so the number on the button is never a mystery. */
   public static String whyLine(ItemStack stack, UUID player) {
      int repairs = boughtCount(player);
      if (repairs <= 0) {
         return "§7This is the first repair you have bought here.";
      }
      return "§7You have bought §f" + repairs + "§7 repair(s) here - the next one is dearer for it.";
   }

   private static int enchantCount(ItemStack stack) {
      try {
         return stack.getEnchantments().size();
      } catch (Exception e) {
         return 0;
      }
   }

   public static int boughtCount(UUID player) {
      return player == null ? 0 : bought.getOrDefault(player, 0);
   }

   /** Mends the tool outright. Returns an error message, or null when the work was done. */
   public static String repair(ServerPlayer player, ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return "Put a damaged tool in the middle slot first.";
      }
      if (!stack.isDamageableItem()) {
         return "That is not something that wears out.";
      }
      if (stack.getDamageValue() <= 0) {
         return "Nothing to mend - that tool is already whole.";
      }
      long price = priceFor(stack, player.getUUID());
      long balance = EconomyManager.balance(player.getUUID());
      if (balance < price) {
         return "You can't afford that: " + Chat.moneyStr(price) + " (you have " + Chat.moneyStr(balance) + ").";
      }
      if (!EconomyManager.takeCash(player.getUUID(), price)) {
         return "You can't afford that right now.";
      }
      stack.setDamageValue(0);
      bought.merge(player.getUUID(), 1, Integer::sum);
      if (player.level() instanceof ServerLevel level) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0, player.getZ(), 24, 0.4, 0.5, 0.4, 0.06);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.8F, 1.4F);
      }
      SoundUtil.play(player, ModSounds.JOB_COMPLETE);
      return null;
   }
}
