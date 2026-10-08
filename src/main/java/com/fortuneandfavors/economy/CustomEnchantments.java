package com.fortuneandfavors.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

public final class CustomEnchantments {
   public static final String LIFESTEAL = "ff_lifesteal";
   public static final String STICKY = "ff_sticky";
   public static final String SEISMIC = "ff_seismic";
   public static final String MIND_WRACK = "ff_mindwrack";
   public static final String FROSTBITE = "ff_frostbite";
   /** Custom Quick Charge IV for crossbows; it is stored separately from the vanilla cap. */
   public static final String QUICK_CHARGE = "ff_quick_charge";
   public static final String PARRY = "ff_parry";
   public static final String COUNTER = "ff_counter";
   public static final String SOULBIND = "ff_soulbind";
   public static final String POINT_BLANK = "ff_point_blank";
   public static final String DEADEYE = "ff_deadeye";
   public static final String CRAB_CLAW = "ff_crab_claw";
   public static final String CURSE_UNDYING = "ff_curse_undying";
   public static final String FLARE = "ff_flare";
   public static final String GUARD = "ff_guard";
   public static final String HANDYMAN = "ff_handyman";
   public static final String HUNGER_ASPECT = "ff_hunger_aspect";
   public static final String MOONWALK = "ff_moonwalk";
   public static final String OCEAN_HEART = "ff_ocean_heart";
   public static final String REJUVENATION = "ff_rejuvenation";
   public static final String SAFE_LANDING = "ff_safe_landing";
   public static final String VELOCITY = "ff_velocity";
   /** The Time Lord's enchant: every hit ages the target. The fifth stack
    *  detonates in one burst and briefly cripples their movement. */
   public static final String AGING = "ff_aging";
   /** REALLY rare ancient enchant: 1.8 combat on swords/axes (-30% damage),
    *  instant full-power bow shots, and fast knockback fishing rods. */
   public static final String LEGACY = "ff_legacy";
   // ---- the four newest raid bosses each carry their own enchant ----
   /** Clockwork King: hits discharge an arc that overloads nearby machines. */
   public static final String OVERCLOCK = "ff_overclock";
   /** Starbound Magister: hits call a star down on the target, cleaving around it. */
   public static final String STARFALL = "ff_starfall";
   /** Void Shaper: hits drag the target in and tear through their guard. */
   public static final String VOIDREND = "ff_voidrend";
   /** Emerald Sovereign: hits mark a debtor; kills shake loose their tribute. */
   public static final String LEVY = "ff_levy";
   /**
    * RARE. The one enchant that is mob-only by construction: its damage lives inside
    * the branch of the damage pipeline that only runs against non-players, so "never
    * players" is not a condition somebody could get wrong later - there is no line of
    * code where a player victim could reach it.
    */
   public static final String ASTRAL = "ff_astral";
   public static final int MAX_LEVEL = 5;
   public static final int QUICK_CHARGE_MAX_LEVEL = 4;
   public static final String TOME_TYPE_KEY = "ff_tome";
   public static final String[] ALL_KEYS = new String[]{
      LIFESTEAL, STICKY, SEISMIC, MIND_WRACK, FROSTBITE, QUICK_CHARGE,
      PARRY, COUNTER, SOULBIND, POINT_BLANK, DEADEYE, CRAB_CLAW,
      CURSE_UNDYING, FLARE, GUARD, HANDYMAN, HUNGER_ASPECT, MOONWALK,
      OCEAN_HEART, REJUVENATION, SAFE_LANDING, VELOCITY, LEGACY, AGING,
      OVERCLOCK, STARFALL, VOIDREND, LEVY, ASTRAL
   };

   private CustomEnchantments() {
   }

   public static int levelOf(ItemStack stack, String key) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
         if (data == null) {
            return 0;
         }

         int level = data.copyTag().getInt(key).orElse(0);
         return Math.max(0, Math.min(maxLevelOf(key), level));
      } else {
         return 0;
      }
   }

   /** Fast negative path for callers that only need a yes/no: vanilla stacks
    *  carry no custom data at all, and maxLevelOf(key) == 1 for most enchants,
    *  so a copied-and-boxed read is avoided for the common "enchanted book /
    *  plain sword" case that hits this dozens of times per tick per player. */
   public static boolean has(ItemStack stack, String key) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      if (maxLevelOf(key) <= 1) {
         // Level is only ever 1 for these - containment alone answers it.
         CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
         return data != null && data.copyTag().contains(key);
      }
      return levelOf(stack, key) > 0;
   }

   public static int maxLevelOf(String key) {
      return switch (key) {
         case QUICK_CHARGE -> QUICK_CHARGE_MAX_LEVEL;
         case AGING -> 3;
         case OVERCLOCK, STARFALL, VOIDREND, LEVY, ASTRAL -> 3;
         case COUNTER, POINT_BLANK, CRAB_CLAW -> 3;
         case HUNGER_ASPECT, MOONWALK -> 2;
         case REJUVENATION, SAFE_LANDING -> 5;
         case PARRY, SOULBIND, DEADEYE, CURSE_UNDYING, FLARE, GUARD, HANDYMAN,
            OCEAN_HEART, VELOCITY, LEGACY -> 1;
         default -> MAX_LEVEL;
      };
   }

   public static int apply(ItemStack stack, String key, int level) {
      if (stack != null && !stack.isEmpty()) {
         int newLevel = Math.max(1, Math.min(maxLevelOf(key), level));
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putInt(key, newLevel);
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
         stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
         if (LEGACY.equals(key)) {
            reapplyLegacyModifiers(stack);
         }
         refresh(stack);
         return newLevel;
      } else {
         return 0;
      }
   }

   /** True when the stack is a sword/axe carrying Legacy (the attribute-bearer).
    *  Perf: the registry-tag check runs first - it's a plain enum compare - so
    *  every non-sword/non-axe item (the overwhelmingly common case in a held
    *  slot) skips the custom-data copy entirely. */
   public static boolean isLegacyWeapon(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !(stack.is(net.minecraft.tags.ItemTags.SWORDS) || stack.is(net.minecraft.tags.ItemTags.AXES))) {
         return false;
      }
      return has(stack, LEGACY);
   }

   private static final net.minecraft.resources.Identifier LEGACY_SPEED_ID = com.fortuneandfavors.FortuneFavorsMod.id("legacy_attack_speed");
   private static final net.minecraft.resources.Identifier LEGACY_DMG_ID = com.fortuneandfavors.FortuneFavorsMod.id("legacy_damage_cut");

   /** Legacy swords/axes swing instantly (huge attack speed) but hit 30%
    *  softer (0.7x damage). Idempotent: only adds once per modifier. */
   public static void reapplyLegacyModifiers(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !isLegacyWeapon(stack)) {
         return;
      }
      net.minecraft.world.item.component.ItemAttributeModifiers mods = (net.minecraft.world.item.component.ItemAttributeModifiers)stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
      boolean hasSpeed = mods != null && mods.modifiers().stream().anyMatch(e -> e.modifier().id().equals(LEGACY_SPEED_ID));
      boolean hasDmg = mods != null && mods.modifiers().stream().anyMatch(e -> e.modifier().id().equals(LEGACY_DMG_ID));
      if (hasSpeed && hasDmg) {
         return;
      }
      net.minecraft.world.item.component.ItemAttributeModifiers current = mods != null ? mods : net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY;
      if (!hasSpeed) {
         current = current.withModifierAdded(
            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED,
            new net.minecraft.world.entity.ai.attributes.AttributeModifier(LEGACY_SPEED_ID, 1024.0, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE),
            net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND
         );
      }
      if (!hasDmg) {
         current = current.withModifierAdded(
            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
            new net.minecraft.world.entity.ai.attributes.AttributeModifier(LEGACY_DMG_ID, -0.3, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
            net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND
         );
      }
      stack.set(DataComponents.ATTRIBUTE_MODIFIERS, current);
   }

   public static String tomeEnchantment(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
         if (data == null) {
            return null;
         }

         String type = data.copyTag().getString("ff_tome").orElse("");
         return type.isEmpty() ? null : type;
      } else {
         return null;
      }
   }

   public static boolean isTome(ItemStack stack) {
      return tomeEnchantment(stack) != null;
   }

   public static int tomeLevel(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
         String key = tomeEnchantment(stack);
         return data == null ? 0 : Math.max(1, Math.min(maxLevelOf(key), data.copyTag().getInt("level").orElse(1)));
      } else {
         return 0;
      }
   }

   public static ItemStack tome(String key, int level) {
      int lvl = Math.max(1, Math.min(maxLevelOf(key), level));
      ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
      CompoundTag tag = new CompoundTag();
      tag.putString("ff", tomeType(key));
      tag.putString("ff_tome", key);
      tag.putInt("level", lvl);
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§" + color(key) + "§l" + displayName(key) + " Tome " + roman(lvl)));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Dropped by a raid boss. Fuse it onto"),
               Component.literal("§7any weapon or legendary in the Item Forge,"),
               Component.literal("§7or right-click it with the weapon in hand."),
               Component.literal("§8" + description(key, lvl))
            )
         )
      );
      return stack;
   }

   /**
    * As {@link #tome(String, int)}, but the first lore line says where it was found.
    *
    * <p>The castle's library shelves these, and a tome that insists a raid boss dropped it
    * while it is sitting in a bookcase reads as a copy-paste. The rest of the tome is the
    * same item, so everything that already knows how to read one keeps working.
    */
   public static ItemStack tome(String key, int level, String source) {
      ItemStack stack = tome(key, level);
      int lvl = Math.max(1, Math.min(maxLevelOf(key), level));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7" + source + " Fuse it onto"),
               Component.literal("§7any weapon or legendary in the Item Forge,"),
               Component.literal("§7or right-click it with the weapon in hand."),
               Component.literal("§8" + description(key, lvl))
            )
         )
      );
      return stack;
   }

   public static int randomTomeLevel(Random random) {
      // Boss-dropped tomes: I-V, weighted so the top levels stay special
      // (V ~6%, IV ~14%, III ~30%, II ~30%, I ~20%).
      int r = random.nextInt(100);
      if (r < 6) {
         return 5;
      } else if (r < 20) {
         return 4;
      } else if (r < 50) {
         return 3;
      } else if (r < 80) {
         return 2;
      } else {
         return 1;
      }
   }

   public static String displayName(String key) {
      return switch (key) {
         case "ff_lifesteal" -> "Life Steal";
         case "ff_sticky" -> "Sticky";
         case "ff_seismic" -> "Seismic";
         case "ff_mindwrack" -> "Mind Wrack";
         case "ff_frostbite" -> "Frostbite";
         case QUICK_CHARGE -> "Quick Charge";
         case AGING -> "Aging";
         case PARRY -> "Parry";
         case COUNTER -> "Counter";
         case SOULBIND -> "Soulbind";
         case POINT_BLANK -> "Point Blank";
         case DEADEYE -> "Deadeye";
         case CRAB_CLAW -> "Crab Claw";
         case CURSE_UNDYING -> "Curse of Undying";
         case FLARE -> "Flare";
         case GUARD -> "Guard";
         case HANDYMAN -> "Handyman";
         case HUNGER_ASPECT -> "Hunger Aspect";
         case MOONWALK -> "Moonwalk";
         case OCEAN_HEART -> "Ocean Heart";
         case REJUVENATION -> "Rejuvenation";
         case SAFE_LANDING -> "Safe Landing";
         case VELOCITY -> "Velocity";
         case LEGACY -> "Legacy";
         case OVERCLOCK -> "Overclock";
         case STARFALL -> "Starfall";
         case VOIDREND -> "Voidrend";
         case LEVY -> "Sovereign's Levy";
         case ASTRAL -> "Astral";
         default -> key;
      };
   }

   public static char color(String key) {
      if ("ff_lifesteal".equals(key)) {
         return 'c';
      } else if ("ff_sticky".equals(key)) {
         return 'a';
      } else if ("ff_mindwrack".equals(key)) {
         return 'd';
      } else {
         return switch (key) {
            case "ff_lifesteal" -> 'c';
            case "ff_sticky" -> 'a';
            case "ff_mindwrack" -> 'd';
            case "ff_frostbite" -> 'b';
            case AGING -> '5';
            case QUICK_CHARGE -> 'e';
            case PARRY, GUARD -> '9';
            case COUNTER, POINT_BLANK, VELOCITY -> '6';
            case SOULBIND, DEADEYE, OCEAN_HEART -> '5';
            case CURSE_UNDYING, FLARE -> 'c';
            case LEGACY -> '5';
            case OVERCLOCK -> '6';
            case STARFALL -> 'b';
            case VOIDREND -> '5';
            case LEVY -> 'a';
            case ASTRAL -> 'd';
            case REJUVENATION, SAFE_LANDING -> 'd';
            default -> '7';
         };
      }
   }

   private static String description(String key, int level) {
      return switch (key) {
         case "ff_lifesteal" -> "Heal " + level + " HP on every hit.";
         case "ff_sticky" -> "Enemies you hit are slowed for " + level * 5 + "s.";
         case "ff_seismic" -> "Hits hurl the enemy back and up.";
         case "ff_mindwrack" -> "Hits scramble the target's senses.";
         case "ff_frostbite" -> "Hits chill the enemy, slowing and freezing them.";
         case AGING -> "Every hit ages the target. At 5 stacks the years catch up in one burst and their legs fail.";
         case QUICK_CHARGE -> "Crossbows reload faster; combines with vanilla Quick Charge.";
         case PARRY -> "Perfectly block a projectile to send it back where you face.";
         case COUNTER -> "After blocking, your next melee hit deals bonus damage for 3 seconds. 15s cooldown.";
         case SOULBIND -> "This item stays with you through death and is protected by NKI.";
         case POINT_BLANK -> "Projectiles deal bonus damage at close range.";
         case DEADEYE -> "Stand still for 3 seconds to charge a homing guaranteed-hit crossbow shot.";
         case CRAB_CLAW -> "Increases block and entity interaction range.";
         case CURSE_UNDYING -> "The enchanted tool saves you from death once, then is consumed.";
         case FLARE -> "Arrows burst like fireworks when they land or hit.";
         case GUARD -> "Swords, axes, and maces can block melee attacks. Raise it just as the blow lands to perfect-block.";
         case HANDYMAN -> "Keeps repair costs low; cannot be paired with Mending.";
         case HUNGER_ASPECT -> "Hits apply Hunger for 5 seconds.";
         case MOONWALK -> "Reduces gravity while gliding with an Elytra.";
         case OCEAN_HEART -> "Grants Conduit Power while wearing this chestplate in water.";
         case REJUVENATION -> "+" + (4 * level) + " max health; cannot pair with Protection.";
         case SAFE_LANDING -> "Increases the safe landing height of an Elytra.";
         case VELOCITY -> "Doubles the speed of bow, crossbow, and trident projectiles.";
         case LEGACY -> "RESTORES 1.8 COMBAT: swords & axes swing instantly but deal 30% less damage; bows charge 10% faster; fishing rods cast faster and the bobber knocks back.";
         case OVERCLOCK -> "Hits arc into nearby foes, overloading them with Weakness and Slowness.";
         case STARFALL -> "Hits call a star down, cleaving everything near the target for " + (1 + level) + " extra damage.";
         case VOIDREND -> "Hits drag the target in and tear " + (level * 8) + "% through their guard.";
         case LEVY -> "Hits mark the target for tribute; slain debtors surrender emeralds " + (level * 20) + "% of the time.";
         case ASTRAL -> "RARE. Deals +" + (level * 7) + "% damage to mobs - and never to players.";
         default -> "";
      };
   }

   public static String roman(int n) {
      return switch (n) {
         case 1 -> "I";
         case 2 -> "II";
         case 3 -> "III";
         case 4 -> "IV";
         case 5 -> "V";
         default -> String.valueOf(n);
      };
   }

   public static String tomeType(String key) {
      return switch (key) {
         case "ff_lifesteal" -> "lifesteal_tome";
         case "ff_sticky" -> "sticky_tome";
         case "ff_seismic" -> "seismic_tome";
         case "ff_mindwrack" -> "mindwrack_tome";
         case "ff_frostbite" -> "frostbite_tome";
         case AGING -> "aging_tome";
         case QUICK_CHARGE -> "quick_charge_tome";
         case OVERCLOCK -> "overclock_tome";
         case STARFALL -> "starfall_tome";
         case VOIDREND -> "voidrend_tome";
         case LEVY -> "levy_tome";
         case ASTRAL -> "astral_tome";
         default -> "tome";
      };
   }

   public static void refresh(ItemStack stack) {
      ItemLore existing = (ItemLore)stack.get(DataComponents.LORE);
      List<Component> lines = new ArrayList<>();
      if (existing != null) {
         for (Component line : existing.lines()) {
            String s = line.getString();
            if (!isEnchantLoreLine(s)) {
               lines.add(line);
            }
         }
      }

      int ls = levelOf(stack, "ff_lifesteal");
      int sticky = levelOf(stack, "ff_sticky");
      int seismic = levelOf(stack, "ff_seismic");
      int mindWrack = levelOf(stack, "ff_mindwrack");
      int frostbite = levelOf(stack, "ff_frostbite");
      int quickCharge = levelOf(stack, QUICK_CHARGE);
      int parry = levelOf(stack, PARRY);
      int counter = levelOf(stack, COUNTER);
      int soulbind = levelOf(stack, SOULBIND);
      int pointBlank = levelOf(stack, POINT_BLANK);
      int deadeye = levelOf(stack, DEADEYE);
      int crabClaw = levelOf(stack, CRAB_CLAW);
      int curseUndying = levelOf(stack, CURSE_UNDYING);
      int flare = levelOf(stack, FLARE);
      int guard = levelOf(stack, GUARD);
      int handyman = levelOf(stack, HANDYMAN);
      int hungerAspect = levelOf(stack, HUNGER_ASPECT);
      int moonwalk = levelOf(stack, MOONWALK);
      int oceanHeart = levelOf(stack, OCEAN_HEART);
      int rejuvenation = levelOf(stack, REJUVENATION);
      int safeLanding = levelOf(stack, SAFE_LANDING);
      int velocity = levelOf(stack, VELOCITY);
      int legacy = levelOf(stack, LEGACY);
      int aging = levelOf(stack, AGING);
      int overclock = levelOf(stack, OVERCLOCK);
      int starfall = levelOf(stack, STARFALL);
      int voidrend = levelOf(stack, VOIDREND);
      int levy = levelOf(stack, LEVY);
      int astral = levelOf(stack, ASTRAL);
      if (ls > 0) {
         lines.add(Component.literal("§cLife Steal " + roman(ls)));
      }

      if (sticky > 0) {
         lines.add(Component.literal("§aSticky " + roman(sticky)));
      }

      if (seismic > 0) {
         lines.add(Component.literal("§7Seismic " + roman(seismic)));
      }

      if (mindWrack > 0) {
         lines.add(Component.literal("§dMind Wrack " + roman(mindWrack)));
      }

      if (frostbite > 0) {
         lines.add(Component.literal("§bFrostbite " + roman(frostbite)));
      }

      if (quickCharge > 0) lines.add(Component.literal("§eQuick Charge " + roman(quickCharge)));
      if (parry > 0) lines.add(Component.literal("§9Parry " + roman(parry)));
      if (counter > 0) lines.add(Component.literal("§6Counter " + roman(counter)));
      if (soulbind > 0) lines.add(Component.literal("§5Soulbind " + roman(soulbind)));
      if (pointBlank > 0) lines.add(Component.literal("§6Point Blank " + roman(pointBlank)));
      if (deadeye > 0) lines.add(Component.literal("§5Deadeye " + roman(deadeye)));
      if (crabClaw > 0) lines.add(Component.literal("§7Crab Claw " + roman(crabClaw)));
      if (curseUndying > 0) lines.add(Component.literal("§cCurse of Undying " + roman(curseUndying)));
      if (flare > 0) lines.add(Component.literal("§cFlare " + roman(flare)));
      if (guard > 0) lines.add(Component.literal("§9Guard " + roman(guard)));
      if (handyman > 0) lines.add(Component.literal("§7Handyman " + roman(handyman)));
      if (hungerAspect > 0) lines.add(Component.literal("§2Hunger Aspect " + roman(hungerAspect)));
      if (moonwalk > 0) lines.add(Component.literal("§bMoonwalk " + roman(moonwalk)));
      if (oceanHeart > 0) lines.add(Component.literal("§3Ocean Heart " + roman(oceanHeart)));
      if (rejuvenation > 0) lines.add(Component.literal("§dRejuvenation " + roman(rejuvenation)));
      if (safeLanding > 0) lines.add(Component.literal("§aSafe Landing " + roman(safeLanding)));
      if (velocity > 0) lines.add(Component.literal("§6Velocity " + roman(velocity)));
      if (legacy > 0) lines.add(Component.literal("§5Legacy " + roman(legacy)));
      if (aging > 0) lines.add(Component.literal("§5Aging " + roman(aging)));
      if (overclock > 0) lines.add(Component.literal("§6Overclock " + roman(overclock)));
      if (starfall > 0) lines.add(Component.literal("§bStarfall " + roman(starfall)));
      if (voidrend > 0) lines.add(Component.literal("§5Voidrend " + roman(voidrend)));
      if (levy > 0) lines.add(Component.literal("§aSovereign's Levy " + roman(levy)));
      if (astral > 0) lines.add(Component.literal("§dAstral " + roman(astral)));

      stack.set(DataComponents.LORE, new ItemLore(lines));
   }

   private static boolean isEnchantLoreLine(String s) {
      return s.startsWith("§cLife Steal")
         || s.startsWith("§7Life Steal")
         || s.startsWith("§aSticky")
         || s.startsWith("§7Sticky")
         || s.startsWith("§7Seismic")
         || s.startsWith("§dMind Wrack")
         || s.startsWith("§bFrostbite")
         || s.startsWith("§eQuick Charge")
         || s.startsWith("§9Parry") || s.startsWith("§6Counter") || s.startsWith("§5Soulbind")
         || s.startsWith("§6Point Blank") || s.startsWith("§5Deadeye") || s.startsWith("§7Crab Claw")
         || s.startsWith("§cCurse of Undying") || s.startsWith("§cFlare") || s.startsWith("§9Guard")
         || s.startsWith("§7Handyman") || s.startsWith("§2Hunger Aspect") || s.startsWith("§bMoonwalk")
         || s.startsWith("§3Ocean Heart") || s.startsWith("§dRejuvenation") || s.startsWith("§aSafe Landing")
         || s.startsWith("§6Velocity") || s.startsWith("§5Legacy") || s.startsWith("§5Aging")
         || s.startsWith("§6Overclock") || s.startsWith("§bStarfall") || s.startsWith("§5Voidrend")
         || s.startsWith("§aSovereign's Levy") || s.startsWith("§dAstral");
   }
}
