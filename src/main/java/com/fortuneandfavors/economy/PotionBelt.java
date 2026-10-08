package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.SoundUtil;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;

/**
 * The Potion Belt: four draughts on your hip, a brewing stand around them, and a click to drink.
 *
 * <p>Drinking a potion in this game costs you a hand, a moment and - if anybody is watching - the
 * second it takes to raise the bottle. The belt is the answer to all three: right-click it, click a
 * flask, and the effect is already on you. What it is not is a bottomless one: every flask carries
 * a small number of draughts and goes on its own cooldown, so a belt is a decision about which
 * three seconds of a fight you want to spend, and the belt as a whole has its own cooldown so it
 * cannot be a chain of four buffs in a row.
 *
 * <p>Filling a flask is brewing, and that is the whole economy of the item. The belt used to sell
 * refills for money, which made it a vending machine wearing a brewing stand's icon: the flasks
 * were a purchase rather than a craft, and the one thing a belt should reward - carrying the right
 * ingredients into a fight and knowing the recipes - was not part of it at all. Now the belt's own
 * window holds a brewing bay (a bottle, an ingredient and blaze powder to burn) and it brews the way
 * a brewing stand brews, through the game's own recipe book: water and nether wart become an awkward
 * potion, awkward and sugar become Swiftness, and the moment a finished potion of one of the belt's
 * four kinds is sitting in the bottle slot the belt drinks it and that flask is full again.
 *
 * <p>The look-up is the game's, not a table of its own - {@code PotionBrewing} answers what mixes
 * with what, so a potion this mod has never heard of still brews, and a recipe added by a data pack
 * still works. It also means the belt cannot be wrong about brewing, only about which flasks it has.
 */
public final class PotionBelt {
   /** How many flasks a belt carries. */
   public static final int FLASKS = 4;
   /** Draughts in one flask. */
   public static final int CHARGES = 3;
   /** Ticks before the same flask can be drunk again - twelve seconds. */
   public static final long FLASK_COOLDOWN = 240L;
   /** Ticks before ANY flask can be drunk again - the belt itself has to settle. */
   public static final long SHARED_COOLDOWN = 60L;
   /**
    * How long one brew takes in the belt's own bay, in ticks - twenty seconds, the same as a brewing
    * stand. It is deliberately not faster: a belt that brews quicker than the block it copies is a
    * block nobody would ever place.
    */
   public static final int BREW_TICKS = 400;

   public static final String[] NAMES = new String[]{"Speed", "Fire Resistance", "Night Vision", "Strength"};
   private static final String[] KEYS = new String[]{"speed", "fireres", "night", "strength"};
   /** Durations in ticks, per flask - a belt brew is stronger than a sip, but not by much. */
   private static final int[] DURATION = new int[]{20 * 150, 20 * 180, 20 * 240, 20 * 90};

   private PotionBelt() {
   }

   // ------------------------------------------------------------------ the belt's own state
   //
   // Everything lives in the item's own custom data, which is the one place a stack can keep state
   // without the mod registering anything: the same {@code CustomData} the type tag uses, so the
   // belt travels with its charges through chests, shulkers, trades and deaths with no extra
   // plumbing at all.

   private static CompoundTag tag(ItemStack belt) {
      CustomData data = belt.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
      return data.copyTag();
   }

   private static void store(ItemStack belt, CompoundTag tag) {
      belt.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   private static String chargeKey(int flask) {
      return "ff_belt_" + KEYS[flask];
   }

   private static String readyKey(int flask) {
      return "ff_belt_ready_" + KEYS[flask];
   }

   /** Charges left in one flask. A brand new belt is full - it is made filled. */
   public static int charges(ItemStack belt, int flask) {
      if (belt == null || belt.isEmpty() || flask < 0 || flask >= FLASKS) {
         return 0;
      }
      CompoundTag tag = tag(belt);
      return tag.contains(chargeKey(flask)) ? Math.max(0, tag.getIntOr(chargeKey(flask), 0)) : CHARGES;
   }

   private static void setCharges(ItemStack belt, int flask, int value) {
      CompoundTag tag = tag(belt);
      tag.putInt(chargeKey(flask), Math.max(0, Math.min(CHARGES, value)));
      store(belt, tag);
   }

   /** Fills one flask to the brim - what a finished brew does to the belt. */
   public static void fill(ItemStack belt, int flask) {
      if (belt == null || belt.isEmpty() || flask < 0 || flask >= FLASKS) {
         return;
      }
      setCharges(belt, flask, CHARGES);
   }

   /** The site tick this flask is next ready on. */
   public static long readyAt(ItemStack belt, int flask) {
      if (belt == null || belt.isEmpty()) {
         return 0L;
      }
      return tag(belt).getLongOr(readyKey(flask), 0L);
   }

   private static void setReadyAt(ItemStack belt, int flask, long tick) {
      CompoundTag tag = tag(belt);
      tag.putLong(readyKey(flask), tick);
      store(belt, tag);
   }

   /** The tick the belt as a whole is next ready on. */
   public static long sharedReadyAt(ItemStack belt) {
      return belt == null || belt.isEmpty() ? 0L : tag(belt).getLongOr("ff_belt_ready_all", 0L);
   }

   private static void setSharedReadyAt(ItemStack belt, long tick) {
      CompoundTag tag = tag(belt);
      tag.putLong("ff_belt_ready_all", tick);
      store(belt, tag);
   }

   public static int totalCharges(ItemStack belt) {
      int n = 0;
      for (int i = 0; i < FLASKS; i++) {
         n += charges(belt, i);
      }
      return n;
   }

   /** Seconds a flask has left on its cooldown, or 0 when it is ready. */
   public static long secondsLeft(ItemStack belt, int flask, long now) {
      long left = readyAt(belt, flask) - now;
      return Math.max(0L, left / 20L);
   }

   /** Seconds the belt itself has left, or 0 when it is ready. */
   public static long sharedSecondsLeft(ItemStack belt, long now) {
      return Math.max(0L, (sharedReadyAt(belt) - now) / 20L);
   }

   // ------------------------------------------------------------------ what goes in which flask
   //
   // The four flasks are four potions, and the potions are the game's own: a belt that carried its
   // own private list of recipes would be a belt that quietly stops matching the game. So a finished
   // potion is matched by *identity* with the registry's potion holders, which is also what lets the
   // long and strong variants of each count - a Long Night Vision is still a Night Vision flask.

   /** The potions that fill one flask: the base one and its long and strong brews. */
   public static java.util.List<Holder<Potion>> potionsFor(int flask) {
      return switch (flask) {
         case 0 -> java.util.List.of(Potions.SWIFTNESS, Potions.LONG_SWIFTNESS, Potions.STRONG_SWIFTNESS);
         case 1 -> java.util.List.of(Potions.FIRE_RESISTANCE, Potions.LONG_FIRE_RESISTANCE);
         case 2 -> java.util.List.of(Potions.NIGHT_VISION, Potions.LONG_NIGHT_VISION);
         default -> java.util.List.of(Potions.STRENGTH, Potions.LONG_STRENGTH, Potions.STRONG_STRENGTH);
      };
   }

   /**
    * Which flask a bottle fills, or -1 when it fills none of them.
    *
    * <p>Splash and lingering potions do not count: a thrown potion is a different item for a
    * different job, and converting one into a draught on the belt would be a quiet upgrade rather
    * than a recipe. A plain drinkable potion is the whole of what the bay accepts.
    */
   public static int flaskForPotion(ItemStack bottle) {
      if (bottle == null || bottle.isEmpty() || !bottle.is(Items.POTION)) {
         return -1;
      }
      PotionContents contents = bottle.get(DataComponents.POTION_CONTENTS);
      if (contents == null) {
         return -1;
      }
      for (int flask = 0; flask < FLASKS; flask++) {
         for (Holder<Potion> potion : potionsFor(flask)) {
            if (contents.is(potion)) {
               return flask;
            }
         }
      }
      return -1;
   }

   /** The recipe card for one flask: the second step of the brew, said in one line. */
   public static String recipeLine(int flask) {
      return switch (flask) {
         case 0 -> "§8Awkward + §7Sugar §8\u2192 Speed";
         case 1 -> "§8Awkward + §7Magma Cream §8\u2192 Fire Resistance";
         case 2 -> "§8Awkward + §7Golden Carrot §8\u2192 Night Vision";
         default -> "§8Awkward + §7Blaze Powder §8\u2192 Strength";
      };
   }

   /** The first step, which every one of the four recipes starts with. */
   public static final String BASE_STEP = "§8Water Bottle + §7Nether Wart §8\u2192 Awkward Potion";

   /**
    * Drinks one flask. Returns an error message for the player, or null when the draught went down.
    */
   public static String drink(ServerPlayer player, ItemStack belt, int flask) {
      if (flask < 0 || flask >= FLASKS) {
         return "That is not a flask.";
      }
      if (charges(belt, flask) <= 0) {
         return "Your " + NAMES[flask] + " flask is empty - brew one in the bay and it fills itself.";
      }
      long now = player.level().getGameTime();
      long shared = sharedReadyAt(belt) - now;
      if (shared > 0L) {
         return "The belt needs a moment - " + (shared / 20L + 1L) + "s.";
      }
      long ready = readyAt(belt, flask) - now;
      if (ready > 0L) {
         return "Your " + NAMES[flask] + " flask is still settling - " + (ready / 20L + 1L) + "s.";
      }
      player.addEffect(effectFor(flask));
      setCharges(belt, flask, charges(belt, flask) - 1);
      setReadyAt(belt, flask, now + FLASK_COOLDOWN);
      setSharedReadyAt(belt, now + SHARED_COOLDOWN);
      if (player.level() instanceof ServerLevel level) {
         level.sendParticles(
            ParticleTypes.ITEM_SLIME, player.getX(), player.getY() + 1.4, player.getZ(), 18, 0.35, 0.4, 0.35, 0.08
         );
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.8F, 1.3F);
      }
      return null;
   }

   /** The belt drinks its own brew: a finished potion just paid for one flask. */
   public static String pour(ServerPlayer player, ItemStack belt, int flask) {
      if (flask < 0 || flask >= FLASKS) {
         return "That is not a flask.";
      }
      fill(belt, flask);
      SoundUtil.play(player, ModSounds.TRANSFER);
      if (player.level() instanceof ServerLevel level) {
         level.sendParticles(ParticleTypes.ENCHANT, player.getX(), player.getY() + 1.2, player.getZ(), 22, 0.5, 0.6, 0.5, 0.4);
         level.sendParticles(ParticleTypes.ITEM_SLIME, player.getX(), player.getY() + 1.4, player.getZ(), 12, 0.3, 0.3, 0.3, 0.05);
      }
      return null;
   }

   private static MobEffectInstance effectFor(int flask) {
      return switch (flask) {
         case 0 -> new MobEffectInstance(MobEffects.SPEED, DURATION[0], 1, false, true, true);
         case 1 -> new MobEffectInstance(MobEffects.FIRE_RESISTANCE, DURATION[1], 0, false, true, true);
         case 2 -> new MobEffectInstance(MobEffects.NIGHT_VISION, DURATION[2], 0, false, true, true);
         default -> new MobEffectInstance(MobEffects.STRENGTH, DURATION[3], 0, false, true, true);
      };
   }

   /** What the effect actually is, for the flask's own label. */
   public static String effectLine(int flask) {
      return switch (flask) {
         case 0 -> "§7Speed II for §f" + (DURATION[0] / 20) + "s";
         case 1 -> "§7Fire Resistance for §f" + (DURATION[1] / 20) + "s";
         case 2 -> "§7Night Vision for §f" + (DURATION[2] / 20) + "s";
         default -> "§7Strength for §f" + (DURATION[3] / 20) + "s";
      };
   }
}
