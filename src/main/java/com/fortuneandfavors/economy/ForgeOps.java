package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;

public final class ForgeOps {
   private ForgeOps() {
   }

   public static ItemStack forge(ServerPlayer player, ItemStack legendary, List<ItemStack> materialSlots) {
      if (legendary == null || legendary.isEmpty()) {
         Chat.msg(player, "&cPut a legendary (or a backpack) in the forge to upgrade it.");
         return ItemStack.EMPTY;
      }

      // Repairing Membrane: any item or armor + a membrane = full repair.
      for (ItemStack s : materialSlots) {
         if (s != null && !s.isEmpty() && ModItems.isRepairMembrane(s)) {
            if (legendary.getCount() > 1) {
               Chat.msg(player, "&cSplit the stack - repair one item at a time.");
               return ItemStack.EMPTY;
            }
            if (!legendary.isDamageableItem()) {
               Chat.msg(player, "&cThat item can't be damaged - nothing to repair.");
               return ItemStack.EMPTY;
            }
            if (legendary.getDamageValue() <= 0) {
               Chat.msg(player, "&cThat item is already at full durability.");
               return ItemStack.EMPTY;
            }
            s.shrink(1);
            legendary.setDamageValue(0);
            Chat.raw(player, "&bRepaired &r" + legendary.getHoverName().getString() + "&b to full durability!");
            SoundUtil.play(player, ModSounds.TRANSFER);
            forgeBurst(player);
            SkillManager.addEnchantingXp(player, 25L);
            return legendary;
         }
      }

      if (ModItems.isBackpack(legendary)) {
         return forgeBackpack(player, legendary, materialSlots);
      }

      if (ModItems.isBundle(legendary)) {
         return forgeBundle(player, legendary, materialSlots);
      }

      // The End's own set. Unlike every other legendary in this forge, these are not
      // *upgraded* into existence - they are assembled. A player walks out of the dragon
      // fight with a Heart of the End and five Dragon Scales, drops those next to a base
      // weapon, and gets the weapon the fight was for.
      //
      // This sits above the rune branch below on purpose: a netherite sword counts as
      // "socketable", so with the whole End set in the slots the rune branch would reach
      // it first and refuse the Heart with "weapons and armor take a Rune" - which is
      // exactly the shape of "I put the heart in and nothing happened".
      if (isEnderBaseWeapon(legendary) && holdsEndMaterial(materialSlots)) {
         return assembleEnderWeapon(player, legendary, materialSlots);
      }

      // Taking one of those three further. This has to sit *above* the rune branch for the
      // same reason the assembly does, and it has to be tested before the base-weapon branch
      // could match: a Voidfang is a netherite sword with an `ff` type, so `isEnderBaseWeapon`
      // deliberately refuses it and the two branches cannot overlap.
      if (ModItems.isEnderLegendary(legendary) && holdsEndMaterial(materialSlots)) {
         return upgradeEnderWeapon(player, legendary, materialSlots);
      }

      // Rune fusion: any sword, axe or armor piece + a rune in the material
      // slot = socketed. Each item takes one of every rune of its kind;
      // extra copies of the same rune are refused so a misclick can't eat
      // duplicates.
      if (RuneManager.isSocketable(legendary)) {
         for (ItemStack s : materialSlots) {
            if (s == null || s.isEmpty()) {
               continue;
            }
            String runeType = RuneManager.typeOfRune(s);
            if (runeType == null) {
               // An upgrade? Every weapon and every armour piece counts as
               // "socketable", so a legendary being upgraded used to land here
               // with its set's upgrade material (Mythical Gelatin, Wither
               // Essence, a Raiders Item Upgrader...) and be refused with this
               // rune message - which is why no socketable legendary could ever
               // be upgraded. When the material is the right one for this
               // legendary, fall through to the upgrade branch instead.
               if (ModItems.isForgeLegendary(legendary) && isUpgradeMaterialFor(legendary, s)) {
                  break;
               }

               Chat.msg(player, "&cWeapons and armor take a §dRune§c in the material slot (or an enchantment tome in the tome slot).");
               return ItemStack.EMPTY;
            }
            if (legendary.getCount() > 1) {
               Chat.msg(player, "&cSplit the stack - socket one item at a time.");
               return ItemStack.EMPTY;
            }
            boolean weaponRune = RuneManager.isWeaponRune(runeType);
            if (weaponRune && !RuneManager.isWeapon(legendary)) {
               Chat.msg(player, "&c" + RuneManager.runeName(runeType) + "§c only fits weapons.");
               return ItemStack.EMPTY;
            }
            if (!weaponRune && !RuneManager.isArmor(legendary)) {
               Chat.msg(player, "&c" + RuneManager.runeName(runeType) + "§c only fits armor pieces.");
               return ItemStack.EMPTY;
            }
            if (ModItems.hasRune(legendary, runeType)) {
               Chat.msg(player, "&cThat " + (weaponRune ? "weapon" : "armor piece") + " already has the " + RuneManager.runeName(runeType) + "§c socketed.");
               return ItemStack.EMPTY;
            }
            s.shrink(1);
            ModItems.applyRune(legendary, runeType);
            Chat.raw(player, "§d§lRune fused!§r §7" + RuneManager.runeName(runeType) + "§7 is now part of the " + (weaponRune ? "blade" : "armor") + ".");
            SoundUtil.play(player, ModSounds.TRANSFER);
            forgeBurst(player);
            SkillManager.addEnchantingXp(player, 40L);
            Advancements.grant(player, "rune_forger");
            grantAllRunes(player, legendary);
            return legendary;
         }
      }

      if (legendary.is(Items.DIAMOND_SWORD) && !ModItems.isDistantMemorySword(legendary)) {
         for (ItemStack s : materialSlots) {
            if (s != null && !s.isEmpty() && ModItems.isDistantMemoryShard(s)) {
               s.shrink(1);
               ItemStack result = legendary.copy();
               ModItems.setType(result, "distant_memory_sword");
               result.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
               result.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(4550061.0F), List.of(), List.of(), List.of()));
               result.set(DataComponents.MAX_DAMAGE, 3122);
               result.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lDistant Memory"));
               ModItems.refreshDistantMemoryLore(result);
               // Guarantee the enchanting table accepts the blade (26.2 needs
               // enchantable + empty enchantments components).
               ModItems.ensureDistantMemoryEnchantable(result);
               AttributeModifier dmgMod = new AttributeModifier(FortuneFavorsMod.id("distant_memory_damage"), 4.0, Operation.ADD_VALUE);
               AttributeModifier spdMod = new AttributeModifier(FortuneFavorsMod.id("distant_memory_attack_speed"), 1021.0, Operation.ADD_VALUE);
               result.set(
                  DataComponents.ATTRIBUTE_MODIFIERS,
                  ItemAttributeModifiers.builder()
                     .add(Attributes.ATTACK_DAMAGE, dmgMod, EquipmentSlotGroup.MAINHAND)
                     .add(Attributes.ATTACK_SPEED, spdMod, EquipmentSlotGroup.MAINHAND)
                     .build()
               );
               Chat.raw(player, "&7Forged: &r" + result.getHoverName().getString() + "&7!");
               Chat.msg(player, "&7A blade of the &dDistant Memory &7is born.");
               SoundUtil.play(player, ModSounds.TRANSFER);
               forgeBurst(player);
               SkillManager.addEnchantingXp(player, 200L);
               Advancements.grant(player, "distant_memory_forged");
               return result;
            }
         }

         Chat.msg(player, "&cPut a &bThe Echoing Shard Of A Distant Memory &cin the material slot to forge a Distant Memory Sword.");
         return ItemStack.EMPTY;
      } else {
         if (!ModItems.isForgeLegendary(legendary)) {
            Chat.msg(player, "&cPut a legendary in the forge to upgrade it.");
            return ItemStack.EMPTY;
         }

         if (legendary.getCount() > 1) {
            Chat.msg(player, "&cSplit the stack - the legendary must be a single item.");
            return ItemStack.EMPTY;
         }

         int tier = ModItems.tierOf(legendary);
         if (tier >= 3) {
            Chat.msg(player, "&cThat legendary is already maxed at Tier III.");
            return ItemStack.EMPTY;
         }

         // What this set upgrades with, asked rather than re-derived. Every set's material is
         // decided in exactly one place (upgradeMaterialFor) and every accept test goes through
         // isUpgradeMaterialFor, so a set can no longer be in the forge but missing from the
         // list of what it accepts - which is how the Sea and Gale sets came to be upgraded by
         // a Mindbinder's material while having none of their own. Adding a set is one line
         // there and nothing here can drift away from it.
         ItemStack wanted = upgradeMaterialFor(legendary);
         if (wanted.isEmpty()) {
            Chat.msg(player, "&cThat legendary has no set material to upgrade it with.");
            return ItemStack.EMPTY;
         }
         int count = 0;

         for (ItemStack s : materialSlots) {
            if (s == null || s.isEmpty()) {
               continue;
            }
            if (!isUpgradeMaterialFor(legendary, s)) {
               Chat.msg(
                  player,
                  "&cThis legendary upgrades with " + materialName(wanted) + " - clear the other items out."
               );
               return ItemStack.EMPTY;
            }
            count += s.getCount();
         }

         int required = tier == 1 ? 1 : 2;
         if (count < required) {
            Chat.msg(
               player,
               "&cYou need "
                  + required
                  + " "
                  + materialName(wanted)
                  + "(s) to reach Tier "
                  + CustomEnchantments.roman(tier + 1)
                  + " - you have "
                  + count
                  + "."
            );
            return ItemStack.EMPTY;
         }

         int left = required;

         for (ItemStack s : materialSlots) {
            if (s != null && !s.isEmpty()) {
               int take = Math.min(s.getCount(), left);
               s.shrink(take);
               left -= take;
               if (left <= 0) {
                  break;
               }
            }
         }

         ItemStack result = ModItems.withTier(legendary, tier + 1);
         Chat.raw(player, "&7Forged: &r" + result.getHoverName().getString() + "&7!");
         Chat.msg(player, "&7Upgraded to &eTier " + CustomEnchantments.roman(tier + 1) + "&7.");
         SoundUtil.play(player, ModSounds.TRANSFER);
         forgeBurst(player);
         SkillManager.addEnchantingXp(player, 60L + tier * 40L);
         if (tier + 1 >= 3) {
            Advancements.grant(player, "grand_forger");
         }

         return result;
      }
   }

   /**
    * One of the three plain weapons the End's three legendaries are built from.
    *
    * <p>Named by base item rather than by custom-model id, and required to carry no {@code ff}
    * type at all - so a Distant Memory or a wither blade, which are also netherite swords, can
    * never be eaten by this recipe.
    */
   public static boolean isEnderBaseWeapon(ItemStack stack) {
      return stack != null
         && !stack.isEmpty()
         && ModItems.typeOf(stack) == null
         && (stack.is(Items.NETHERITE_SWORD) || stack.is(Items.BOW) || stack.is(Items.MACE));
   }

   /** True when the End's two materials are anywhere in the forge's input slots. */
   private static boolean holdsEndMaterial(List<ItemStack> materialSlots) {
      for (ItemStack s : materialSlots) {
         if (s != null && !s.isEmpty() && (ModItems.isHeartOfTheEnd(s) || ModItems.isDragonScale(s))) {
            return true;
         }
      }
      return false;
   }

   /**
    * Assembles one of the End's three legendary weapons: a base weapon, one Heart of the End,
    * and five Dragon Scales.
    *
    * <p>The scale count is a real requirement rather than one scale standing in for five: the
    * dragon drops five to everybody, so five is what the recipe asks for, and the heart is the
    * part that makes it a decision - the set is one heart per player per fight.
    *
    * <p>Always returns a stack: the assembled weapon, or {@link ItemStack#EMPTY} with the reason
    * already in chat. The caller only reaches here when the slots hold End material, so a refusal
    * is never silent.
    */
   private static ItemStack assembleEnderWeapon(ServerPlayer player, ItemStack base, List<ItemStack> materialSlots) {
      if (base.getCount() > 1) {
         Chat.msg(player, "&cSplit the stack - the weapon must be a single item.");
         return ItemStack.EMPTY;
      }

      EndMaterials held = readEndMaterials(player, materialSlots);
      if (held == null) {
         return ItemStack.EMPTY;
      }
      if (held.hearts() < END_HEART_COST) {
         Chat.msg(player, "&cYou need a &5Heart of the End§c to forge this weapon.");
         return ItemStack.EMPTY;
      }
      if (held.scales() < END_SCALE_COST) {
         Chat.msg(player, "&cYou need &5" + END_SCALE_COST + " Dragon Scales§c to forge this weapon - you have " + held.scales() + ".");
         return ItemStack.EMPTY;
      }

      boolean bow = base.is(Items.BOW);
      boolean mace = base.is(Items.MACE);
      ItemStack result = bow ? ModItems.starfall() : (mace ? ModItems.enderheart() : ModItems.voidfang());

      takeEndMaterials(materialSlots, END_HEART_COST, END_SCALE_COST);

      Chat.raw(player, "&5Forged: &r" + result.getHoverName().getString() + "&5!");
      Chat.msg(player, "&7The heart is spent; the weapon remembers the fight it came from.");
      SoundUtil.play(player, ModSounds.TRANSFER);
      forgeBurst(player);
      SkillManager.addEnchantingXp(player, 300L);
      return result;
   }

   /** One Heart of the End builds one weapon; the fight pays one heart per player. */
   public static final int END_HEART_COST = 1;
   /** Five is what the dragon hands out, so five is what the recipe asks for. */
   public static final int END_SCALE_COST = 5;
   /**
    * The awakened tier costs one more Heart and five more Scales.
    *
    * <p>The same price as building the weapon in the first place, on purpose: awakening is not a
    * second grind, it is the fight's own yield - one more dragon's worth of the two things the
    * dragon actually drops - spent to push a weapon you already earned into its next form.
    */
   public static final int END_AWAKENED_HEART_COST = 1;
   public static final int END_AWAKENED_SCALE_COST = 5;

   /** How much End material the forge's slots hold. */
   private record EndMaterials(int hearts, int scales) {
   }

   /**
    * Reads the two End materials out of the forge's slots, or refuses the whole craft.
    *
    * <p>Shared by the assembly and the upgrade so the two can never disagree about what a
    * slot is allowed to hold - and so "clear the other items out" is said once, in one place,
    * rather than twice with slightly different wording.
    *
    * @return the counts, or {@code null} when a slot holds something that is not End material
    *         (the refusal has already been said in chat by then)
    */
   private static EndMaterials readEndMaterials(ServerPlayer player, List<ItemStack> materialSlots) {
      int hearts = 0;
      int scales = 0;
      for (ItemStack s : materialSlots) {
         if (s == null || s.isEmpty()) {
            continue;
         }
         if (ModItems.isHeartOfTheEnd(s)) {
            hearts += s.getCount();
         } else if (ModItems.isDragonScale(s)) {
            scales += s.getCount();
         } else {
            Chat.msg(player, "&cThe End's weapons take only a Heart of the End and Dragon Scales - clear the other items out.");
            return null;
         }
      }
      return new EndMaterials(hearts, scales);
   }

   /** Spends exactly this much End material out of the slots, never more than is needed. */
   private static void takeEndMaterials(List<ItemStack> materialSlots, int hearts, int scales) {
      int heartsLeft = hearts;
      int scalesLeft = scales;
      for (ItemStack s : materialSlots) {
         if (s == null || s.isEmpty()) {
            continue;
         }
         if (ModItems.isHeartOfTheEnd(s) && heartsLeft > 0) {
            int take = Math.min(s.getCount(), heartsLeft);
            s.shrink(take);
            heartsLeft -= take;
         } else if (ModItems.isDragonScale(s) && scalesLeft > 0) {
            int take = Math.min(s.getCount(), scalesLeft);
            s.shrink(take);
            scalesLeft -= take;
         }
      }
   }

   /**
    * Reforges one of the End's three into its awakened form.
    *
    * <p>The weapon itself goes into the forge as the base, which is why this is a separate
    * craft rather than a third material: you are spending what you already built, not building
    * a second one beside it. The numbers are pinned by the audit, because "the upgrade costs a
    * dragon's yield" is the whole reason the tier means anything.
    */
   private static ItemStack upgradeEnderWeapon(ServerPlayer player, ItemStack weapon, List<ItemStack> materialSlots) {
      if (weapon.getCount() > 1) {
         Chat.msg(player, "&cSplit the stack - the weapon must be a single item.");
         return ItemStack.EMPTY;
      }

      EndMaterials held = readEndMaterials(player, materialSlots);
      if (held == null) {
         return ItemStack.EMPTY;
      }
      if (held.hearts() < END_AWAKENED_HEART_COST) {
         Chat.msg(
            player,
            "&cAwakening needs &5" + END_AWAKENED_HEART_COST + " Heart of the End§c - you have " + held.hearts() + "."
         );
         return ItemStack.EMPTY;
      }
      if (held.scales() < END_AWAKENED_SCALE_COST) {
         Chat.msg(
            player,
            "&cAwakening needs &5" + END_AWAKENED_SCALE_COST + " Dragon Scales§c - you have " + held.scales() + "."
         );
         return ItemStack.EMPTY;
      }

      ItemStack result = ModItems.awaken(weapon);
      if (result.isEmpty()) {
         // Unreachable through the dispatch, but a silent no-op here would eat the materials.
         Chat.msg(player, "&cThat weapon has no awakened form.");
         return ItemStack.EMPTY;
      }

      takeEndMaterials(materialSlots, END_AWAKENED_HEART_COST, END_AWAKENED_SCALE_COST);

      Chat.raw(player, "&dAwakened: &r" + result.getHoverName().getString() + "&d!");
      Chat.msg(player, "&7The rift closes sooner. The sky answers faster. The heart beats louder.");
      SoundUtil.play(player, ModSounds.TRANSFER);
      forgeBurst(player);
      SkillManager.addEnchantingXp(player, 500L);
      return result;
   }

   private static ItemStack forgeBackpack(ServerPlayer player, ItemStack backpack, List<ItemStack> materialSlots) {
      if (backpack.getCount() > 1) {
         Chat.msg(player, "&cSplit the stack - the backpack must be a single item.");
         return ItemStack.EMPTY;
      }

      int tier = ModItems.backpackTier(backpack);

      // Ender Pouch: link the backpack to the owner's ender chest.
      boolean hasPouch = false;
      boolean pouchOnly = true;

      for (ItemStack s : materialSlots) {
         if (s != null && !s.isEmpty()) {
            if (ModItems.isEnderPouch(s)) {
               hasPouch = true;
            } else {
               pouchOnly = false;
            }
         }
      }

      if (hasPouch) {
         if (!pouchOnly) {
            Chat.msg(player, "&cThe Ender Pouch upgrade needs only the pouch - clear the other items out.");
            return ItemStack.EMPTY;
         }
         if (ModItems.isEnderBackpack(backpack)) {
            Chat.msg(player, "&cThis backpack is already linked to your ender chest.");
            return ItemStack.EMPTY;
         }

         for (ItemStack s : materialSlots) {
            if (s != null && !s.isEmpty() && ModItems.isEnderPouch(s)) {
               s.shrink(1);
               break;
            }
         }

         ItemStack result = backpack.copy();
         ModItems.setEnderBackpack(result, true);
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lEnder Backpack §7[" + ModItems.backpackTierName(tier) + "]"));
         List<Component> lore = new ArrayList<>();
         lore.add(Component.literal("§7A portable storage sack."));
         lore.add(Component.literal("§7Right-click to open §f" + tier * 9 + "§7 slots of storage."));
         lore.add(Component.literal("§5Sneak-right-click to open your ender chest."));
         if (tier < 5) {
            lore.add(Component.literal("§7Upgrade it in the Item Forge with 8 of the"));
            lore.add(Component.literal("§7next material's ingots (" + ModItems.backpackUpgradeIngotName(tier) + ")."));
         } else {
            lore.add(Component.literal("§7This backpack is maxed at Netherite."));
         }

         lore.add(Component.literal("§8Non-placeable · Cannot be stacked"));
         result.set(DataComponents.LORE, new ItemLore(lore));
         Chat.raw(player, "&7Forged: &r" + result.getHoverName().getString() + "&7!");
         Chat.msg(player, "&7Your backpack is now linked to your &5ender chest&7 - sneak-right-click it to open.");
         SoundUtil.play(player, ModSounds.BACKPACK);
         forgeBurst(player);
         SkillManager.addEnchantingXp(player, 60L + tier * 20L);
         return result;
      }

      // Crafting Table: build a Workbench INTO the backpack. The table is
      // consumed and the pack gains the toolbar Workbench button forever.
      boolean hasTable = false;
      boolean tableOnly = true;

      for (ItemStack s : materialSlots) {
         if (s != null && !s.isEmpty()) {
            if (s.is(Items.CRAFTING_TABLE)) {
               hasTable = true;
            } else {
               tableOnly = false;
            }
         }
      }

      if (hasTable) {
         if (!tableOnly) {
            Chat.msg(player, "&cThe Crafting Table upgrade needs only the table - clear the other items out.");
            return ItemStack.EMPTY;
         }
         if (ModItems.backpackHasWorkbench(backpack)) {
            Chat.msg(player, "&cThis backpack already has a built-in Workbench.");
            return ItemStack.EMPTY;
         }

         for (ItemStack s : materialSlots) {
            if (s != null && !s.isEmpty() && s.is(Items.CRAFTING_TABLE)) {
               s.shrink(1);
               break;
            }
         }

         int wbTier = ModItems.backpackTier(backpack);
         ItemStack result = backpack.copy();
         ModItems.setBackpackWorkbench(result, true);
         List<Component> wbLore = new ArrayList<>();
         wbLore.add(Component.literal("§7A portable storage sack."));
         wbLore.add(Component.literal("§7Right-click to open §f" + wbTier * 9 + "§7 slots of storage."));
         wbLore.add(Component.literal("§6Built-in Workbench§7 - open the pack and click the"));
         wbLore.add(Component.literal("§6crafting table§7 button for a 3x3 anywhere."));
         if (wbTier < 5) {
            wbLore.add(Component.literal("§7Upgrade it in the Item Forge with 8 of the"));
            wbLore.add(Component.literal("§7next material's ingots (" + ModItems.backpackUpgradeIngotName(wbTier) + ")."));
         } else {
            wbLore.add(Component.literal("§7This backpack is maxed at Netherite."));
         }

         wbLore.add(Component.literal("§8Non-placeable · Cannot be stacked"));
         result.set(DataComponents.LORE, new ItemLore(wbLore));
         Chat.raw(player, "&7Forged: &r" + result.getHoverName().getString() + "&7!");
         Chat.msg(player, "&7A crafting table is now built into your backpack - open it and click the &6Workbench&7 button.");
         SoundUtil.play(player, ModSounds.BACKPACK);
         forgeBurst(player);
         SkillManager.addEnchantingXp(player, 40L);
         return result;
      }

      // Jukebox: build MUSIC on the go into the backpack. The jukebox is
      // consumed and the pack gains the toolbar Jukebox button forever.
      boolean hasJukebox = false;
      boolean jukeboxOnly = true;

      for (ItemStack s : materialSlots) {
         if (s != null && !s.isEmpty()) {
            if (s.is(Items.JUKEBOX)) {
               hasJukebox = true;
            } else {
               jukeboxOnly = false;
            }
         }
      }

      if (hasJukebox) {
         if (!jukeboxOnly) {
            Chat.msg(player, "&cThe Jukebox upgrade needs only the jukebox - clear the other items out.");
            return ItemStack.EMPTY;
         }
         if (ModItems.backpackHasJukebox(backpack)) {
            Chat.msg(player, "&cThis backpack already has a built-in Jukebox.");
            return ItemStack.EMPTY;
         }

         for (ItemStack s : materialSlots) {
            if (s != null && !s.isEmpty() && s.is(Items.JUKEBOX)) {
               s.shrink(1);
               break;
            }
         }

         int jbTier = ModItems.backpackTier(backpack);
         ItemStack result = backpack.copy();
         ModItems.setBackpackJukebox(result, true);
         List<Component> jbLore = new ArrayList<>();
         jbLore.add(Component.literal("§7A portable storage sack."));
         jbLore.add(Component.literal("§7Right-click to open §f" + jbTier * 9 + "§7 slots of storage."));
         jbLore.add(Component.literal("§bBuilt-in Jukebox§7 - open the pack, click it with a"));
         jbLore.add(Component.literal("§bmusic disc§7 and the music plays on the go."));
         if (jbTier < 5) {
            jbLore.add(Component.literal("§7Upgrade it in the Item Forge with 8 of the"));
            jbLore.add(Component.literal("§7next material's ingots (" + ModItems.backpackUpgradeIngotName(jbTier) + ")."));
         } else {
            jbLore.add(Component.literal("§7This backpack is maxed at Netherite."));
         }

         jbLore.add(Component.literal("§8Non-placeable · Cannot be stacked"));
         result.set(DataComponents.LORE, new ItemLore(jbLore));
         Chat.raw(player, "&7Forged: &r" + result.getHoverName().getString() + "&7!");
         Chat.msg(player, "&7A jukebox is now built into your backpack - open it and click the &bJukebox&7 button with a music disc.");
         SoundUtil.play(player, ModSounds.BACKPACK);
         forgeBurst(player);
         SkillManager.addEnchantingXp(player, 40L);
         return result;
      }

      // The Kitchen: fuse a Portable Furnace or a Portable Campfire INTO the pack, so it cooks out
      // of the pack wherever the player is. One kitchen per pack - the two are different machines
      // (see BackpackKitchen), and a pack is not a kitchen with two fires in it.
      String kitchenKind = null;
      boolean kitchenOnly = true;

      for (ItemStack s : materialSlots) {
         if (s != null && !s.isEmpty()) {
            if (BackpackKitchen.kindFor(s) != null && kitchenKind == null) {
               kitchenKind = BackpackKitchen.kindFor(s);
            } else {
               kitchenOnly = false;
            }
         }
      }

      if (kitchenKind != null) {
         if (!kitchenOnly) {
            Chat.msg(player, "&cThe Kitchen upgrade needs only the Portable Furnace or the Portable Campfire - clear the other items out.");
            return ItemStack.EMPTY;
         }
         if (BackpackKitchen.hasKitchen(backpack)) {
            Chat.msg(player, "&cThis backpack already has a kitchen built in.");
            return ItemStack.EMPTY;
         }

         for (ItemStack s : materialSlots) {
            if (s != null && !s.isEmpty() && BackpackKitchen.kindFor(s) != null) {
               s.shrink(1);
               break;
            }
         }

         int kTier = ModItems.backpackTier(backpack);
         ItemStack result = backpack.copy();
         BackpackKitchen.attach(result, kitchenKind);
         List<Component> kLore = new ArrayList<>();

         kLore.add(Component.literal("§7A portable storage sack."));
         kLore.add(Component.literal("§7Right-click to open §f" + kTier * 9 + "§7 slots of storage."));
         kLore.add(Component.literal(BackpackKitchen.KIND_CAMPFIRE.equals(kitchenKind)
            ? "§6Built-in Portable Campfire§7 - open the pack and click"
            : "§6Built-in Portable Furnace§7 - open the pack and click"));
         kLore.add(Component.literal(BackpackKitchen.KIND_CAMPFIRE.equals(kitchenKind)
            ? "§6the campfire§7 to cook §ffood§7 from the pack, for nothing."
            : "§6the furnace§7 to smelt what the pack carries, burning"));
         if (BackpackKitchen.KIND_FURNACE.equals(kitchenKind)) {
            kLore.add(Component.literal("§6fuel§7 out of the pack as it works."));
         }
         if (kTier < 5) {
            kLore.add(Component.literal("§7Upgrade it in the Item Forge with 8 of the"));
            kLore.add(Component.literal("§7next material's ingots (" + ModItems.backpackUpgradeIngotName(kTier) + ")."));
         } else {
            kLore.add(Component.literal("§7This backpack is maxed at Netherite."));
         }

         kLore.add(Component.literal("§8Non-placeable · Cannot be stacked"));
         result.set(DataComponents.LORE, new ItemLore(kLore));
         Chat.raw(player, "&7Forged: &r" + result.getHoverName().getString() + "&7!");
         Chat.msg(player, "&7A " + BackpackKitchen.label(kitchenKind) + " is now built into your backpack - open it and click the &6kitchen&7 button.");
         SoundUtil.play(player, ModSounds.BACKPACK);
         forgeBurst(player);
         SkillManager.addEnchantingXp(player, 30L);
         return result;
      }

      if (tier >= 5) {
         Chat.msg(player, "&cThat backpack is already maxed at Netherite.");
         return ItemStack.EMPTY;
      }

      Item need = ModItems.backpackUpgradeIngot(tier);
      if (need == null) {
         return ItemStack.EMPTY;
      }

      String needName = ModItems.backpackUpgradeIngotName(tier);
      int count = 0;

      for (ItemStack s : materialSlots) {
         if (s != null && !s.isEmpty()) {
            if (!s.is(need)) {
               Chat.msg(player, "&cBackpacks upgrade with 8 " + needName + " - clear the other items out.");
               return ItemStack.EMPTY;
            }

            count += s.getCount();
         }
      }

      if (count < 8) {
         Chat.msg(player, "&cYou need 8 " + needName + " to reach the " + ModItems.backpackTierName(tier + 1) + " backpack - you have " + count + ".");
         return ItemStack.EMPTY;
      }

      int left = 8;

      for (ItemStack s : materialSlots) {
         if (s != null && !s.isEmpty()) {
            int take = Math.min(s.getCount(), left);
            s.shrink(take);
            left -= take;
            if (left <= 0) {
               break;
            }
         }
      }

      ItemStack result = backpack.copy();
      ModItems.setBackpackTier(result, tier + 1);
      result.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lBackpack §7[" + ModItems.backpackTierName(tier + 1) + "]"));
      result.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7A portable storage sack."),
               Component.literal("§7Right-click to open §f" + (tier + 1) * 9 + "§7 slots of storage."),
               Component.literal("§7Upgrade it in the Item Forge with 8 of the"),
               Component.literal("§7next material's ingots (" + ModItems.backpackUpgradeIngotName(tier + 1) + ")."),
               Component.literal("§8Non-placeable · Cannot be stacked")
            )
         )
      );
      Chat.raw(player, "&7Forged: &r" + result.getHoverName().getString() + "&7!");
      Chat.msg(player, "&7Upgraded to the &e" + ModItems.backpackTierName(tier + 1) + "&7 backpack (" + (tier + 1) * 9 + " slots).");
      SoundUtil.play(player, ModSounds.BACKPACK);
      forgeBurst(player);
      SkillManager.addEnchantingXp(player, 40L + tier * 30L);
      return result;
   }

   private static ItemStack forgeBundle(ServerPlayer player, ItemStack bundle, List<ItemStack> materialSlots) {
      if (bundle.getCount() > 1) {
         Chat.msg(player, "&cSplit the stack - the bundle must be a single item.");
         return ItemStack.EMPTY;
      }

      int tier = ModItems.bundleTier(bundle);
      if (tier >= 3) {
         Chat.msg(player, "&cThat bundle is already maxed at Netherite.");
         return ItemStack.EMPTY;
      }

      Item need = ModItems.bundleUpgradeIngot(tier);
      if (need == null) {
         return ItemStack.EMPTY;
      }

      String needName = ModItems.bundleUpgradeIngotName(tier);
      int count = 0;

      for (ItemStack s : materialSlots) {
         if (s != null && !s.isEmpty()) {
            if (!s.is(need)) {
               Chat.msg(player, "&cBundles upgrade with 8 " + needName + " - clear the other items out.");
               return ItemStack.EMPTY;
            }

            count += s.getCount();
         }
      }

      if (count < 8) {
         Chat.msg(player, "&cYou need 8 " + needName + " to reach the " + ModItems.bundleTierName(tier + 1) + " bundle - you have " + count + ".");
         return ItemStack.EMPTY;
      }

      int left = 8;

      for (ItemStack s : materialSlots) {
         if (s != null && !s.isEmpty()) {
            int take = Math.min(s.getCount(), left);
            s.shrink(take);
            left -= take;
            if (left <= 0) {
               break;
            }
         }
      }

      ItemStack result = bundle.copy();
      ModItems.setBundleTier(result, tier + 1);
      result.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lBundle §7[" + ModItems.bundleTierName(tier + 1) + "]"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7A stitched carry-sack - right-click to open §f" + ModItems.bundleSlots(tier + 1) + "§7 slots."));
      if (tier + 1 < 3) {
         lore.add(Component.literal("§7Upgrade it in the Item Forge with 8 of the"));
         lore.add(Component.literal("§7next material's ingots (" + ModItems.bundleUpgradeIngotName(tier + 1) + ")."));
      } else {
         lore.add(Component.literal("§7This bundle is maxed at Netherite."));
      }

      lore.add(Component.literal("§8Non-placeable · Cannot be stacked"));
      result.set(DataComponents.LORE, new ItemLore(lore));
      Chat.raw(player, "&7Forged: &r" + result.getHoverName().getString() + "&7!");
      Chat.msg(player, "&7Upgraded to the &b" + ModItems.bundleTierName(tier + 1) + "&7 bundle (" + ModItems.bundleSlots(tier + 1) + " slots).");
      SoundUtil.play(player, ModSounds.BACKPACK);
      forgeBurst(player);
      SkillManager.addEnchantingXp(player, 40L + tier * 30L);
      return result;
   }

   /** Core validation + application shared by the forge, quick-fuse and anvil:
    *  returns an error message, or null on success (the enchant is applied to
    *  {@code weapon} in place and the tome consumed unless Tome Saver procs).
    *  Never sends chat messages - callers decide how to surface errors. */
   public static String applyTomeCore(ServerPlayer player, ItemStack weapon, ItemStack tome) {
      if (weapon == null || weapon.isEmpty()) {
         return "Put an item in the forge to enchant it.";
      }
      if (weapon.getCount() > 1) {
         return "Split the stack - the item to enchant must be a single item.";
      }
      if (tome == null || tome.isEmpty() || !(CustomEnchantments.isTome(tome) || CCEnchantments.isCCTome(tome))) {
         return "Put exactly one enchantment tome (Life Steal / Sticky / Seismic / Mind Wrack / Frostbite / Quick Charge / Chainfire / Sniper / Revolver / Sharpshooter / Recoil / Hunter's Mark / Heavy Bolt / Fragility / Combo / Berserker) next to the item.";
      }
      if (tome.getCount() > 1) {
         return "Split the stack - use a single enchantment tome.";
      }
      boolean cc = CCEnchantments.isCCTome(tome);
      String key;
      int tomeLevel;
      int current;
      int maxLevel;
      String enchantName;
      if (cc) {
         key = CCEnchantments.ccTomeKey(tome);
         tomeLevel = CCEnchantments.levelOf(tome, key);
         current = CCEnchantments.levelOf(weapon, key);
         maxLevel = CCEnchantments.maxLevelOf(key);
         enchantName = CCEnchantments.displayName(key);
      } else {
         key = CustomEnchantments.tomeEnchantment(tome);
         tomeLevel = CustomEnchantments.tomeLevel(tome);
         current = CustomEnchantments.levelOf(weapon, key);
         maxLevel = CustomEnchantments.maxLevelOf(key);
         enchantName = CustomEnchantments.displayName(key);
      }
      boolean soulbind = AdvancedEnchantments.SOULBIND.equals(key);
      if (!soulbind && !cc && !AdvancedEnchantments.isAdvancedKey(key) && !ModItems.isEnchantableWeapon(weapon)) {
         return "That enchantment can only be applied to a compatible weapon.";
      }
      if (!soulbind && !cc && AdvancedEnchantments.isAdvancedKey(key) && !AdvancedEnchantments.canApply(key, weapon)) {
         return "That enchantment cannot be applied to this item.";
      }
      if (!soulbind && cc && !ModItems.isEnchantableWeapon(weapon)) {
         return "That enchantment can only be applied to a compatible weapon.";
      }
      if (soulbind && !isSoulbindTarget(weapon)) {
         return "Soulbind cannot be applied to that item.";
      }
      if (current >= maxLevel) {
         return "That item already has "
            + enchantName
            + " "
            + CCEnchantments.roman(current)
            + " - it is already maxed out at "
            + CCEnchantments.roman(maxLevel)
            + ".";
      }
      if (current >= tomeLevel) {
         return "That item already has "
            + enchantName
            + " "
            + CCEnchantments.roman(current)
            + " - you need a tome of a higher level.";
      }
      float tomeSaver = SkillManager.tomeSaverChance(player.getUUID());
      if (tomeSaver > 0.0F && new Random().nextFloat() < tomeSaver) {
         Chat.msg(player, "§aTome Saver procs! §7The tome is preserved.");
      } else {
         tome.shrink(1);
      }
      if (cc) {
         CCEnchantments.applyLevel(weapon, key, tomeLevel);
      } else {
         CustomEnchantments.apply(weapon, key, tomeLevel);
      }
      normalizeHandymanRepairCost(weapon);
      SoundUtil.play(player, ModSounds.TRANSFER);
      forgeBurst(player);
      SkillManager.addEnchantingXp(player, 40L + tomeLevel * 30L);
      return null;
   }

   /** For the anvil: applies a custom tome to a COPY of the target and returns
    *  the enchanted copy, or null if the combination is invalid. Never mutates
    *  the input stacks (the anvil recomputes results on every slot change). */
   public static ItemStack applyTomeToCopy(ItemStack target, ItemStack tome) {
      if (target == null || target.isEmpty()) {
         return null;
      }
      if (target.getCount() > 1 || tome == null || tome.getCount() != 1) {
         return null;
      }
      boolean cc = CCEnchantments.isCCTome(tome);
      if (!cc && !CustomEnchantments.isTome(tome)) {
         return null;
      }
      String key = cc ? CCEnchantments.ccTomeKey(tome) : CustomEnchantments.tomeEnchantment(tome);
      if (key == null) {
         return null;
      }
      boolean soulbind = AdvancedEnchantments.SOULBIND.equals(key);
      if (!soulbind && !cc && !AdvancedEnchantments.isAdvancedKey(key) && !ModItems.isEnchantableWeapon(target)) {
         return null;
      }
      if (!soulbind && !cc && AdvancedEnchantments.isAdvancedKey(key) && !AdvancedEnchantments.canApply(key, target)) {
         return null;
      }
      if (!soulbind && cc && !ModItems.isEnchantableWeapon(target)) {
         return null;
      }
      int tomeLevel = cc ? CCEnchantments.levelOf(tome, key) : CustomEnchantments.tomeLevel(tome);
      int current = cc ? CCEnchantments.levelOf(target, key) : CustomEnchantments.levelOf(target, key);
      int maxLevel = cc ? CCEnchantments.maxLevelOf(key) : CustomEnchantments.maxLevelOf(key);
      if (current >= maxLevel || current >= tomeLevel) {
         return null;
      }
      ItemStack result = target.copy();
      if (cc) {
         CCEnchantments.applyLevel(result, key, tomeLevel);
      } else {
         CustomEnchantments.apply(result, key, tomeLevel);
      }
      normalizeHandymanRepairCost(result);
      return result;
   }

   /** ENCHANT one weapon/legendary with a tome (forge menu path). Consumes the
    *  tome; on success the enchanted result is returned and the CALLER decides
    *  where it lands. On failure {@link ItemStack#EMPTY} is returned and the
    *  error has already been shown. */
   public static ItemStack enchant(ServerPlayer player, ItemStack legendary, ItemStack tome) {
      boolean cc = tome != null && CCEnchantments.isCCTome(tome);
      String key = tome == null ? null : (cc ? CCEnchantments.ccTomeKey(tome) : CustomEnchantments.tomeEnchantment(tome));
      int tomeLevel = tome == null ? 0 : (cc ? CCEnchantments.levelOf(tome, key) : CustomEnchantments.tomeLevel(tome));
      String err = applyTomeCore(player, legendary, tome);
      if (err != null) {
         Chat.msg(player, "&c" + err);
         return ItemStack.EMPTY;
      }
      Chat.raw(player, "&d" + (cc ? CCEnchantments.coloredName(key) : CustomEnchantments.displayName(key)) + " " + CCEnchantments.roman(tomeLevel) + "&d fused onto your item!");
      return legendary;
   }

   /** Quick-fuse shortcut: right-click a tome with the weapon in your other hand
    *  (or a weapon with the tome in the off-hand) to apply it directly, no forge
    *  needed. Returns an error message, or null on success. */
   public static String quickFuse(ServerPlayer player, ItemStack weapon, ItemStack tome) {
      if (weapon == null || weapon.isEmpty() || !isTargetForTome(weapon, tome)) {
         return "Hold the item in your other hand - tomes fuse onto compatible weapons, armor, shields, and tools.";
      }
      boolean cc = tome != null && CCEnchantments.isCCTome(tome);
      String key = tome == null ? null : (cc ? CCEnchantments.ccTomeKey(tome) : CustomEnchantments.tomeEnchantment(tome));
      int tomeLevel = tome == null ? 0 : (cc ? CCEnchantments.levelOf(tome, key) : CustomEnchantments.tomeLevel(tome));
      String err = applyTomeCore(player, weapon, tome);
      if (err != null) {
         return err;
      }
      Chat.raw(
         player,
         "&d" + (cc ? CCEnchantments.coloredName(key) : CustomEnchantments.displayName(key)) + " " + CCEnchantments.roman(tomeLevel) + "&d fused onto your " + weapon.getHoverName().getString() + "!"
      );
      return null;
   }

   /**
    * True when {@code material} is the upgrade material for this legendary's set.
    * The forge needs this to tell "upgrade me" apart from "socket a rune on me",
    * because armour and weapons qualify as both.
    */
   public static boolean isUpgradeMaterialFor(ItemStack legendary, ItemStack material) {
      ItemStack want = upgradeMaterialFor(legendary);
      return !want.isEmpty()
         && material != null
         && !material.isEmpty()
         && java.util.Objects.equals(ModItems.typeOf(want), ModItems.typeOf(material));
   }

   /**
    * The single upgrade material for this legendary's set, or {@code EMPTY} when
    * the item is not a forge legendary. One source of truth, so the forge's "is
    * this an upgrade?" test and the self-test's "can this be upgraded?" test can
    * never disagree.
    */
   public static ItemStack upgradeMaterialFor(ItemStack legendary) {
      if (legendary == null || legendary.isEmpty()) {
         return ItemStack.EMPTY;
      }
      if (ModItems.isRaidLegendary(legendary)) {
         return ModItems.raidersItemUpgrader();
      }
      if (ModItems.isWitherLegendary(legendary)) {
         return ModItems.kingBone();
      }
      if (ModItems.isSlimeLegendary(legendary)) {
         return ModItems.slimeCore();
      }
      if (ModItems.isGolemLegendary(legendary)) {
         return ModItems.golemCore();
      }
      if (ModItems.isSnowLegendary(legendary)) {
         return ModItems.frozenHeart();
      }
      if (ModItems.isSculkLegendary(legendary)) {
         return ModItems.sculkEssence();
      }
      if (ModItems.isMindLegendary(legendary)) {
         return ModItems.shatteredMind();
      }
      if (ModItems.isScarletLegendary(legendary)) {
         return ModItems.bloodsoakedCore();
      }
      // The two newest sets. Each answers to the boss it came off and to nothing else - before
      // these lines existed the pair had no material at all, and the forge's chain of per-set
      // tests ended in an unconditional "Shattered Mind", so a Leviathan's Grasp was upgraded
      // with a Mindbinder's material. A set with no material must be impossible, not merely
      // unlikely: this method is the one place that decides, and everything else asks it.
      if (ModItems.isSeaLegendary(legendary)) {
         return ModItems.abyssalPearl();
      }
      if (ModItems.isGaleLegendary(legendary)) {
         return ModItems.galeCore();
      }
      return ItemStack.EMPTY;
   }

   /**
    * A forge material's name as a chat line can use it: the item's own name, without its colours.
    *
    * <p>Read off the material rather than written out beside it. The messages this feeds used to
    * name every set's material by hand, a second copy of the mapping, and a set added to one list
    * and not the other told a player to fetch the wrong thing.
    */
   private static String materialName(ItemStack material) {
      return material.getHoverName().getString().replaceAll("§.", "").trim();
   }

   public static ItemStack recycle(ServerPlayer player, ItemStack legendary) {
      // The End's weapons break back down into the hearts that made them, so a set that was
      // forged before a player knew which weapon they wanted is never a dead end.
      //
      // An awakened weapon gives back everything it cost - one heart for the build plus the two
      // the awakening spent - because scrapping it is already a loss of fifteen scales, and a
      // refund that quietly kept two hearts would make the upgrade a trap rather than a choice.
      if (legendary != null && !legendary.isEmpty() && ModItems.isAnyEnderLegendary(legendary)) {
         if (legendary.getCount() > 1) {
            Chat.msg(player, "&cSplit the stack - the legendary must be a single item.");
            return ItemStack.EMPTY;
         }
      boolean awakened = ModItems.isEnderAwakened(legendary);
      // An awakened weapon gives back what it cost: one Heart for the build plus the one the
      // awakening spent.
      int hearts = awakened ? END_HEART_COST + END_AWAKENED_HEART_COST : END_HEART_COST;
         Chat.raw(player, "&7Recycled: &r" + legendary.getHoverName().getString() + "&7 -> &5" + hearts + " Heart of the End&7.");
         SoundUtil.play(player, ModSounds.TRANSFER);
         forgeBurst(player);
         SkillManager.addEnchantingXp(player, awakened ? 200L : 60L);
         ItemStack back = ModItems.heartOfTheEnd();
         back.setCount(hearts);
         return back;
      }

      if (legendary != null && !legendary.isEmpty() && ModItems.isForgeLegendary(legendary)) {
         if (legendary.getCount() > 1) {
            Chat.msg(player, "&cSplit the stack - the legendary must be a single item.");
            return ItemStack.EMPTY;
         } else {
            int tier = ModItems.tierOf(legendary);
            boolean wither = ModItems.isWitherLegendary(legendary);
            boolean slime = ModItems.isSlimeLegendary(legendary);
            boolean golem = ModItems.isGolemLegendary(legendary);
            boolean mind = ModItems.isMindLegendary(legendary);
            boolean snow = ModItems.isSnowLegendary(legendary);
            boolean sculk = ModItems.isSculkLegendary(legendary);
            boolean scarlet = ModItems.isScarletLegendary(legendary);
            ItemStack cores = wither
               ? ModItems.kingBone()
               : (
                  slime
                     ? ModItems.slimeCore()
                     : (
                        golem
                           ? ModItems.golemCore()
                           : (
                              snow
                                 ? ModItems.frozenHeart()
                                 : (sculk ? ModItems.sculkEssence() : (scarlet ? ModItems.bloodsoakedCore() : ModItems.shatteredMind()))
                           )
                     )
               );
            cores.setCount(tier + (hasReclaim(player) ? 1 : 0));
            Chat.raw(
               player,
               "&7Recycled: &r"
                  + legendary.getHoverName().getString()
                  + "&7 -> "
                  + (wither ? "&6" : (slime ? "&a" : (golem ? "&6" : (snow ? "&b" : (sculk ? "&3" : (scarlet ? "&4" : "&5"))))))
                  + tier
                  + (hasReclaim(player) ? "&a+1" : "")
                  + " "
                  + (
                     wither
                        ? "Wither Essence"
                        : (
                           slime
                              ? "Mythical Gelatin"
                              : (
                                 golem
                                    ? "Golem Core"
                                    : (
                                       snow
                                          ? "Frozen Heart"
                                          : (sculk ? "Sculk Essence" : (scarlet ? "Bloodsoaked Core" : "Shattered Mind"))
                                    )
                              )
                        )
                  )
                  + (tier + (hasReclaim(player) ? 1 : 0) == 1 ? "" : "s")
                  + "&7."
            );
            SoundUtil.play(player, ModSounds.TRANSFER);
            forgeBurst(player);
            SkillManager.addEnchantingXp(player, 30L + tier * 20L);
            return cores;
         }
      } else {
         Chat.msg(player, "&cPut a legendary in the forge to recycle it.");
         return ItemStack.EMPTY;
      }
   }

   public static boolean isEnchantableTarget(ItemStack stack) {
      if (stack == null || stack.isEmpty() || stack.is(Items.ENCHANTED_BOOK)) {
         return false;
      }
      return ModItems.isEnchantableWeapon(stack)
         || stack.is(Items.SHIELD)
         || stack.is(Items.ELYTRA)
         || stack.is(ItemTags.CHEST_ARMOR)
         || stack.isDamageableItem();
   }

   /** Soulbind is the one custom enchantment that may target any item. */
   public static boolean isSoulbindTarget(ItemStack stack) {
      return stack != null && !stack.isEmpty() && !stack.is(Items.ENCHANTED_BOOK);
   }

   /** Resolves the target rule from the tome before a UI routes the item. */
   public static boolean isTargetForTome(ItemStack stack, ItemStack tome) {
      if (tome == null || tome.isEmpty()) {
         return false;
      }
      String key = CCEnchantments.isCCTome(tome) ? CCEnchantments.ccTomeKey(tome) : CustomEnchantments.tomeEnchantment(tome);
      return AdvancedEnchantments.SOULBIND.equals(key) ? isSoulbindTarget(stack) : isEnchantableTarget(stack);
   }

   private static void normalizeHandymanRepairCost(ItemStack stack) {
      if (CustomEnchantments.has(stack, CustomEnchantments.HANDYMAN)) {
         stack.set(DataComponents.REPAIR_COST, 0);
      }
   }

   private static boolean hasReclaim(ServerPlayer player) {
      return SkillManager.hasUpgrade(player.getUUID(), "enchanting", "reclaim");
   }

   /** Grant the all_runes / all_armor_runes achievement when the forged item
    *  now holds one of every rune of its kind. */
   private static void grantAllRunes(ServerPlayer player, ItemStack item) {
      if (RuneManager.isWeapon(item)) {
         for (String t : RuneManager.weaponTypes()) {
            if (!ModItems.hasRune(item, t)) {
               return;
            }
         }
         Advancements.grant(player, "all_runes");
      } else if (RuneManager.isArmor(item)) {
         for (String t : RuneManager.armorTypes()) {
            if (!ModItems.hasRune(item, t)) {
               return;
            }
         }
         Advancements.grant(player, "all_armor_runes");
      }
   }

   private static void forgeBurst(ServerPlayer player) {
      if (player.level() instanceof ServerLevel sl) {
         double var16 = player.getX();
         double y = player.getY() + 1.0;
         double z = player.getZ();

         for (int i = 0; i < 3; i++) {
            double r = 0.5 + i * 0.8;
            double ry = y + i * 0.2;

            for (int ix = 0; ix < 12; ix++) {
               double a = ix / 12.0 * Math.PI * 2.0;
               sl.sendParticles(ParticleTypes.END_ROD, var16 + Math.cos(a) * r, ry, z + Math.sin(a) * r, 1, 0.04, 0.08, 0.04, 0.02);
            }
         }

         for (int i = 0; i < 5; i++) {
            sl.sendParticles(ParticleTypes.FLAME, var16, y + i * 0.35, z, 6, 0.25, 0.15, 0.25, 0.05);
            sl.sendParticles(ParticleTypes.SMOKE, var16, y + i * 0.35, z, 3, 0.3, 0.2, 0.3, 0.02);
         }

         sl.sendParticles(ParticleTypes.SMALL_FLAME, var16, y, z, 14, 0.45, 0.5, 0.45, 0.05);
         sl.sendParticles(ParticleTypes.LAVA, var16, y + 0.2, z, 6, 0.3, 0.3, 0.3, 0.06);
         sl.sendParticles(ParticleTypes.ENCHANT, var16, y + 0.6, z, 24, 0.5, 0.6, 0.5, 0.18);
         SoundUtil.play(player, ModSounds.FORGE_ANVIL, 0.85F);
         SoundUtil.play(player, ModSounds.FORGE_SUCCESS, 1.35F);
      }
   }
}
