package com.fortuneandfavors.economy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CCEnchantments — all custom combat enchantments that need Java logic live
 * here (and hang off items via CUSTOM_DATA NBT so they survive saves, work on
 * the server AND client display, and never need a registry entry that could
 * trip the datapack item-model crash).
 *
 * Enchant list:
 *   - Chainfire I (crossbow): fires 4 extra arrows, cannot pair with Infinity.
 *   - Sniper I (crossbow, VERY RARE): arrows fly straight forever (no gravity,
 *     no velocity falloff) until they hit — lag-free even into unloaded chunks.
 *   - Revolver I (crossbow): 6x slower charge, Quick Charge cuts it, 6 shots
 *     loaded per charge.
 *   - Sharpshooter I-III (crossbow): +12% damage per level at long range.
 *   - Recoil I-III (crossbow): firing pushes you backward.
 *   - Hunter's Mark I-III: hit marks a mob; marked mobs take +8% dmg/level.
 *   - Heavy Bolt I-III: arrows knock back harder.
 *   - Curse of Fragility I-III: extra durability loss.
 *   - Combo I-III (swords): consecutive strikes deal more damage.
 *   - Berserker I (chestplate): +20% damage below 50% HP, -8% armor.
 */
public final class CCEnchantments {
   public static final String CHAINFIRE = "ff_chainfire";
   public static final String SNIPER = "ff_sniper";
   public static final String REVOLVER = "ff_revolver";
   public static final String SHARPSHOOTER = "ff_sharpshooter";
   public static final String RECOIL = "ff_recoil";
   public static final String HUNTERS_MARK = "ff_hunters_mark";
   public static final String HEAVY_BOLT = "ff_heavy_bolt";
   public static final String CURSE_FRAGILITY = "ff_curse_fragility";
   public static final String COMBO = "ff_combo";
   public static final String BERSERKER = "ff_berserker";
   public static final int CHAINFIRE_DELAY_TICKS = 3;

   public static final String COMBO_COUNT_TAG = "ff_combo_count";
   public static final String REVOLVER_SHOTS_TAG = "ff_revolver_shots";
   public static final String SNIPER_TAG = "ff_sniper_active";

   /** All CCEnchantments keys, in display order. */
   public static final String[] ALL_KEYS = new String[]{
      CHAINFIRE, SNIPER, REVOLVER, SHARPSHOOTER, RECOIL, HUNTERS_MARK,
      HEAVY_BOLT, CURSE_FRAGILITY, COMBO, BERSERKER
   };

   /**
    * Per-enchant max level. Chainfire / Sniper / Revolver / Berserker are
    * single-level enchants (their effects don't scale); Sharpshooter / Recoil /
    * Hunter's Mark / Heavy Bolt / Fragility / Combo go to III.
    */
   public static int maxLevelOf(String key) {
      return switch (key) {
         case CHAINFIRE, SNIPER, REVOLVER, BERSERKER -> 1;
         default -> 3;
      };
   }

   private static final int COMBO_MAX = 3;
   private static final long MARK_DURATION_TICKS = 240L; // 12s
   private static final Deque<QueuedChainShot> CHAINFIRE_QUEUE = new ArrayDeque<>();

   private CCEnchantments() {
   }

   // ============================================================ NBT helpers

   private static int tagInt(ItemStack stack, String key, int def) {
      if (stack == null || stack.isEmpty()) {
         return def;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? def : data.copyTag().getInt(key).orElse(def);
   }

   private static void tagPutInt(ItemStack stack, String key, int value) {
      if (stack == null || stack.isEmpty()) {
         return;
      }
      CompoundTag tag = ((CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)).copyTag();
      tag.putInt(key, value);
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   private static boolean tagBool(ItemStack stack, String key, boolean def) {
      return tagInt(stack, key, def ? 1 : 0) == 1;
   }

   private static void tagPutBool(ItemStack stack, String key, boolean value) {
      tagPutInt(stack, key, value ? 1 : 0);
   }

   public static int levelOf(ItemStack stack, String key) {
      if (stack == null || stack.isEmpty()) {
         return 0;
      }
      int lvl = tagInt(stack, key, 0);
      return Math.max(0, Math.min(maxLevelOf(key), lvl));
   }

   public static boolean has(ItemStack stack, String key) {
      return levelOf(stack, key) > 0;
   }

   public static int applyLevel(ItemStack stack, String key, int level) {
      if (stack == null || stack.isEmpty()) {
         return 0;
      }
      int lvl = Math.max(1, Math.min(maxLevelOf(key), level));
      tagPutInt(stack, key, lvl);
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      refreshLore(stack);
      return lvl;
   }

   // ============================================================ lore

   public static void refreshLore(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return;
      }
      net.minecraft.world.item.component.ItemLore existing = (net.minecraft.world.item.component.ItemLore)stack.get(DataComponents.LORE);
      java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
      if (existing != null) {
         for (net.minecraft.network.chat.Component line : existing.lines()) {
            String s = line.getString();
            if (!isCCEnchantLine(s)) {
               lines.add(line);
            }
         }
      }
      addLoreLine(lines, levelOf(stack, CHAINFIRE), "§4Chainfire");
      addLoreLine(lines, levelOf(stack, SNIPER), "§fSniper");
      addLoreLine(lines, levelOf(stack, REVOLVER), "§6Revolver");
      addLoreLine(lines, levelOf(stack, SHARPSHOOTER), "§dSharpshooter");
      addLoreLine(lines, levelOf(stack, RECOIL), "§bRecoil");
      addLoreLine(lines, levelOf(stack, HUNTERS_MARK), "§5Hunter's Mark");
      addLoreLine(lines, levelOf(stack, HEAVY_BOLT), "§7Heavy Bolt");
      addLoreLine(lines, levelOf(stack, CURSE_FRAGILITY), "§8Curse of Fragility");
      addLoreLine(lines, levelOf(stack, COMBO), "§aCombo");
      addLoreLine(lines, levelOf(stack, BERSERKER), "§6Berserker");
      addLoreLine(lines, CustomEnchantments.levelOf(stack, CustomEnchantments.QUICK_CHARGE), "§eQuick Charge");
      stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lines));
   }

   private static void addLoreLine(java.util.List<net.minecraft.network.chat.Component> lines, int level, String name) {
      if (level > 0) {
         lines.add(net.minecraft.network.chat.Component.literal(name + " " + roman(level)));
      }
   }

   private static boolean isCCEnchantLine(String s) {
      return s.startsWith("§4Chainfire")
         || s.startsWith("§fSniper")
         || s.startsWith("§6Revolver")
         || s.startsWith("§dSharpshooter")
         || s.startsWith("§bRecoil")
         || s.startsWith("§5Hunter's Mark")
         || s.startsWith("§7Heavy Bolt")
         || s.startsWith("§8Curse of Fragility")
         || s.startsWith("§aCombo")
         || s.startsWith("§6Berserker")
         || s.startsWith("§eQuick Charge");
   }

   // ============================================================ names & descriptions

   public static String displayName(String key) {
      return switch (key) {
         case CHAINFIRE -> "Chainfire";
         case SNIPER -> "Sniper";
         case REVOLVER -> "Revolver";
         case SHARPSHOOTER -> "Sharpshooter";
         case RECOIL -> "Recoil";
         case HUNTERS_MARK -> "Hunter's Mark";
         case HEAVY_BOLT -> "Heavy Bolt";
         case CURSE_FRAGILITY -> "Curse of Fragility";
         case COMBO -> "Combo";
         case BERSERKER -> "Berserker";
         default -> key;
      };
   }

   /** Colored display name for item names, e.g. "§4Chainfire". */
   public static String coloredName(String key) {
      return switch (key) {
         case CHAINFIRE -> "§4Chainfire";
         case SNIPER -> "§fSniper";
         case REVOLVER -> "§6Revolver";
         case SHARPSHOOTER -> "§dSharpshooter";
         case RECOIL -> "§bRecoil";
         case HUNTERS_MARK -> "§5Hunter's Mark";
         case HEAVY_BOLT -> "§7Heavy Bolt";
         case CURSE_FRAGILITY -> "§8Curse of Fragility";
         case COMBO -> "§aCombo";
         case BERSERKER -> "§6Berserker";
         default -> "§7" + key;
      };
   }

   /** Short player-facing description of the enchant at a given level. */
   public static String description(String key, int level) {
      return switch (key) {
         case CHAINFIRE -> "Fires 4 extra arrows. Can't pair with Infinity.";
         case SNIPER -> "VERY RARE - arrows fly straight and never drop until they hit.";
         case REVOLVER -> "6x slower charge, but fires 6 shots before reloading. Works with Infinity - the cylinder never runs dry.";
         case SHARPSHOOTER -> "Deals +" + (12 * level) + "% damage at long range.";
         case RECOIL -> "Firing pushes you backward - shoot down to launch up!";
         case HUNTERS_MARK -> "Hits mark the target; marked mobs take +" + (8 * level) + "% damage from you.";
         case HEAVY_BOLT -> "Arrows knock targets back harder.";
         case CURSE_FRAGILITY -> "Item loses durability faster, but allows stronger enchant combos.";
         case COMBO -> "Consecutive hits on the same target deal more damage (max +" + (12 * level) + "%).";
         case BERSERKER -> "+20% damage below 50% HP, -8% armor effectiveness.";
         default -> "";
      };
   }

   // ============================================================ tome detection

   /** Returns the CCEnchantments key stored on a tome book, or null. */
   public static String ccTomeKey(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return null;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return null;
      }
      CompoundTag tag = data.copyTag();
      for (String key : new String[]{
         CHAINFIRE, SNIPER, REVOLVER, SHARPSHOOTER, RECOIL, HUNTERS_MARK,
         HEAVY_BOLT, CURSE_FRAGILITY, COMBO, BERSERKER
      }) {
         if (tag.getInt(key).orElse(0) > 0) {
            return key;
         }
      }
      return null;
   }

   /** True if the stack is a CCEnchantments tome book (carries a CC enchant key). */
   public static boolean isCCTome(ItemStack stack) {
      return ccTomeKey(stack) != null;
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

   // ============================================================ combo

   /** Increment the combo counter on the attacker's mainhand (every swing). */
   public static void bumpCombo(Player attacker) {
      if (attacker == null || attacker.level().isClientSide()) {
         return;
      }
      ItemStack held = attacker.getMainHandItem();
      if (levelOf(held, COMBO) <= 0) {
         return;
      }
      int count = tagInt(held, COMBO_COUNT_TAG, 0);
      if (count < COMBO_MAX) {
         tagPutInt(held, COMBO_COUNT_TAG, count + 1);
      }
   }

   /** Reset combo (miss, target switch, or getting hit). */
   public static void resetCombo(Player attacker) {
      if (attacker == null || attacker.level().isClientSide()) {
         return;
      }
      tagPutInt(attacker.getMainHandItem(), COMBO_COUNT_TAG, 0);
   }

   public static float comboDamage(Player attacker) {
      if (attacker == null) {
         return 1.0F;
      }
      ItemStack held = attacker.getMainHandItem();
      int lvl = levelOf(held, COMBO);
      if (lvl <= 0) {
         return 1.0F;
      }
      int count = Math.min(COMBO_MAX, tagInt(held, COMBO_COUNT_TAG, 0));
      return 1.0F + count * lvl * 0.04F; // max +12 / +24 / +36%
   }

   /** Called when a melee hit lands. */
   public static void comboOnHit(Player attacker, Entity target) {
      // NO-OP: counter is bumped on swing, read during damage, then left for the
      // next swing; a miss or switch resets via PlayerAttackMixin.
   }

   // ============================================================ hunter's mark

   // attacker UUID -> { target UUID least-bits, expires-tick }
   private static final Map<UUID, long[]> MARKS = new HashMap<>();

   public static void markTarget(ServerPlayer attacker, LivingEntity target, int level) {
      long expires = target.level().getGameTime() + MARK_DURATION_TICKS;
      MARKS.put(attacker.getUUID(), new long[]{target.getUUID().getLeastSignificantBits(), expires});
   }

   public static boolean isMarked(LivingEntity target, Player attacker) {
      long[] m = MARKS.get(attacker.getUUID());
      if (m == null) {
         return false;
      }
      if (m[1] < target.level().getGameTime()) {
         MARKS.remove(attacker.getUUID());
         return false;
      }
      return m[0] == target.getUUID().getLeastSignificantBits();
   }

   // ============================================================ crossbow helpers

   public static boolean isCrossbow(ItemStack stack) {
      return stack != null && !stack.isEmpty() && stack.is(net.minecraft.tags.ItemTags.CROSSBOW_ENCHANTABLE);
   }

   public static boolean isRevolverCrossbow(ItemStack stack) {
      return isCrossbow(stack) && has(stack, REVOLVER);
   }

   public static boolean isSniperCrossbow(ItemStack stack) {
      return isCrossbow(stack) && has(stack, SNIPER);
   }

   public static boolean isChainfireCrossbow(ItemStack stack) {
      return isCrossbow(stack) && has(stack, CHAINFIRE);
   }

   public static boolean hasCustomQuickCharge(ItemStack stack) {
      return isCrossbow(stack) && CustomEnchantments.has(stack, CustomEnchantments.QUICK_CHARGE);
   }

   // ============================================================ revolver

   public static int revolverShotsLeft(ItemStack stack) {
      return tagInt(stack, REVOLVER_SHOTS_TAG, 6);
   }

   public static void setRevolverShots(ItemStack stack, int shots) {
      tagPutInt(stack, REVOLVER_SHOTS_TAG, Math.max(0, Math.min(6, shots)));
   }

   public static int revolverChargeTicks(ItemStack stack, LivingEntity entity) {
      int base = isRevolverCrossbow(stack) ? 20 : 25; // vanilla crossbow charge is 25 ticks
      int vanillaQuickCharge = 0;
      try {
         var reg = entity.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
         vanillaQuickCharge = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(reg.getOrThrow(Enchantments.QUICK_CHARGE), stack);
      } catch (Throwable t) {
      }
      int customQuickCharge = CustomEnchantments.levelOf(stack, CustomEnchantments.QUICK_CHARGE);
      // The custom IV tome is additive with vanilla Quick Charge. Revolvers keep
      // their slower reload profile, while ordinary crossbows use the vanilla
      // five-ticks-per-level reduction with the extra custom level included.
      int quickCharge = vanillaQuickCharge + customQuickCharge;
      if (isRevolverCrossbow(stack)) {
         float mult = Math.max(1.0F, 6.0F - quickCharge);
         return Math.max(1, (int)(base * mult));
      }
      return Math.max(1, base - quickCharge * 5);
   }

   /** Refill the cylinder to 6 shots (the slow reload just completed). */
   public static void reloadRevolver(ServerPlayer sp, ItemStack stack) {
      setRevolverShots(stack, 6);
   }

   /**
    * Runs on the server the moment a revolver bolt is fired (see CrossbowMixin).
    * Spends one shot from the cylinder, refills the cylinder when the slow-reload
    * bolt is spent, and re-arms the next bolt so each trigger pull fires
    * instantly. Returns true if another bolt was armed (cylinder still has
    * shots), false when the cylinder ran dry and the next pull starts the reload.
    */
   public static boolean revolverOnShotFired(ServerPlayer sp, ItemStack stack, Level level) {
      int shots = revolverShotsLeft(stack);
      if (shots <= 0) {
         // The reload-charge bolt just fired — fresh cylinder.
         reloadRevolver(sp, stack);
         shots = 6;
      }
      setRevolverShots(stack, shots - 1);
      if (shots - 1 <= 0) {
         return false; // cylinder empty — next trigger pull starts the slow reload
      }
      // Re-arm the next bolt from the player's ammo. When the revolver carries
      // Infinity (applied through the mod's anvil path), ammo is effectively
      // unlimited: we never consume the player's last arrow and can synthesize a
      // fresh arrow if they are momentarily out, so a full cylinder is always
      // spent before the slow reload.
      ItemStack ammo = sp.getProjectile(stack);
      // Infinity on the revolver (anvil/forge path) means the cylinder never
      // costs arrows: synthesize a bolt whenever ammo is empty, consume nothing.
      boolean infinite = sp.hasInfiniteMaterials() || AdvancedEnchantments.hasInfinity(stack);
      if (ammo == null || ammo.isEmpty()) {
         if (!infinite) {
            return true; // no ammo available - the next pull starts the reload
         }
         ammo = new ItemStack(Items.ARROW, 1); // Infinity: synthesize a bolt
      }
      if (!infinite) {
         ammo.shrink(1);
      }
      ItemStack single = ammo.copy();
      single.setCount(1);
      stack.set(DataComponents.CHARGED_PROJECTILES, net.minecraft.world.item.component.ChargedProjectiles.ofNonEmpty(java.util.List.of(single)));
      return true;
   }

   // ============================================================ creative tomes (in-game books)

   /** Tome book for the creative menu: enchant line + description + hint. */
   public static ItemStack creativeTome(String key, int level) {
      ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
      CCEnchantments.applyLevel(stack, key, level);
      stack.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(coloredName(key) + " " + roman(level)));
      net.minecraft.world.item.component.ItemLore lore = (net.minecraft.world.item.component.ItemLore)stack.get(DataComponents.LORE);
      java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>(lore != null ? lore.lines() : java.util.List.of());
      lines.add(net.minecraft.network.chat.Component.literal("§7" + description(key, level)));
      lines.add(net.minecraft.network.chat.Component.literal("§8Fuse it in an anvil or the Item Forge."));
      stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lines));
      return stack;
   }

   /** True if the book carries the real (registry) Bolt Bringer enchantment. */
   public static boolean isBoltBringerBook(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      try {
         net.minecraft.world.item.enchantment.ItemEnchantments ench = (net.minecraft.world.item.enchantment.ItemEnchantments)stack.get(DataComponents.STORED_ENCHANTMENTS);
         if (ench == null) {
            return false;
         }
         var key = net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.ENCHANTMENT,
            net.minecraft.resources.Identifier.fromNamespaceAndPath("fortuneandfavors", "boltbringer")
         );
         for (net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> holder : ench.keySet()) {
            if (holder.is(key)) {
               return true;
            }
         }
      } catch (Throwable t) {
         // never let a lookup failure break item checks
      }
      return false;
   }

   public static int boltBringerLevel(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return 0;
      }
      try {
         net.minecraft.world.item.enchantment.ItemEnchantments ench = (net.minecraft.world.item.enchantment.ItemEnchantments)stack.get(DataComponents.STORED_ENCHANTMENTS);
         if (ench == null) {
            return 0;
         }
         var key = net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.ENCHANTMENT,
            net.minecraft.resources.Identifier.fromNamespaceAndPath("fortuneandfavors", "boltbringer")
         );
         for (net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> holder : ench.keySet()) {
            if (holder.is(key)) {
               return Math.max(1, Math.min(3, ench.getLevel(holder)));
            }
         }
      } catch (Throwable t) {
         // never let a lookup failure break item checks
      }
      return 0;
   }

   /** Enchanted book carrying the real (registry) Bolt Bringer enchantment.
    *  Because it's a genuine vanilla enchantment, it applies in an anvil and
    *  via the enchanting table natively. Requires a holders lookup (server or
    *  synced client registry). */
   public static ItemStack boltBringerBook(net.minecraft.core.HolderLookup.Provider holders, int level) {
      int lvl = Math.max(1, Math.min(3, level));
      ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
      try {
         var key = net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.ENCHANTMENT,
            net.minecraft.resources.Identifier.fromNamespaceAndPath("fortuneandfavors", "boltbringer")
         );
         net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> holder =
            holders.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(key);
         net.minecraft.world.item.enchantment.ItemEnchantments.Mutable mut =
            new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
         mut.set(holder, lvl);
         stack.set(DataComponents.STORED_ENCHANTMENTS, mut.toImmutable());
      } catch (Throwable t) {
         // registry not available - hand back a plain book rather than crash
      }
      stack.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("§e§lBolt Bringer " + roman(lvl)));
      stack.set(
         DataComponents.LORE,
         new net.minecraft.world.item.component.ItemLore(
            java.util.List.of(
               net.minecraft.network.chat.Component.literal("§7Your strikes summon lightning and thunder."),
               net.minecraft.network.chat.Component.literal("§8Real vanilla enchantment - works in the anvil natively.")
            )
         )
      );
      return stack;
   }

   /** Emits every custom enchantment tome the creative menus should carry: CC
    *  enchants at their per-enchant max levels, the original five at I-V, and
    *  the real Bolt Bringer books at I-III. Shared by the server-side Combat
    *  tab and the client-side Fortune & Favors tab so both stay identical. */
   public static void acceptAllTomes(java.util.function.Consumer<ItemStack> out, net.minecraft.core.HolderLookup.Provider holders) {
      for (String key : ALL_KEYS) {
         for (int lvl = 1; lvl <= maxLevelOf(key); lvl++) {
            out.accept(creativeTome(key, lvl));
         }
      }
      for (String key : CustomEnchantments.ALL_KEYS) {
         for (int lvl = 1; lvl <= CustomEnchantments.maxLevelOf(key); lvl++) {
            out.accept(CustomEnchantments.tome(key, lvl));
         }
      }
      for (int lvl = 1; lvl <= 3; lvl++) {
         out.accept(boltBringerBook(holders, lvl));
      }
   }

   // ============================================================ damage hooks

   public static float applyDamageHooks(
      LivingEntity instance,
      net.minecraft.world.damagesource.DamageSource source,
      float amount,
      com.llamalad7.mixinextras.injector.wrapoperation.Operation<java.lang.Float> original
   ) {
      // CombatGear already called the vanilla armor calculation before this
      // hook. Continue from that result instead of absorbing armor twice.
      float dmg = amount;
      try {
         if (!(instance.level() instanceof ServerLevel)) {
            return dmg;
         }
         if (source.getEntity() instanceof ServerPlayer attacker) {
            ItemStack held = attacker.getMainHandItem();
            boolean projectile = source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile;
            // Combo (melee only).
            if (!projectile) {
               dmg *= comboDamage(attacker);
               dmg *= AdvancedEnchantments.counterMultiplier(attacker, source);
            }
            // Sharpshooter (crossbow, distance-based).
            if (projectile) {
               if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectileEntity && instance instanceof LivingEntity target) {
                  dmg = AdvancedEnchantments.modifyProjectileDamage(projectileEntity, target, dmg);
               }
               int ss = levelOf(held, SHARPSHOOTER);
               if (ss > 0 && attacker.distanceTo(instance) >= 20.0) {
                  dmg *= 1.0F + ss * 0.12F;
               }
            }
            int hunger = CustomEnchantments.levelOf(held, CustomEnchantments.HUNGER_ASPECT);
            if (hunger > 0 && instance instanceof LivingEntity target) {
               target.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.HUNGER, 100, hunger - 1));
            }
            // Hunter's mark.
            int hm = levelOf(held, HUNTERS_MARK);
            if (hm > 0 && instance instanceof LivingEntity living) {
               markTarget(attacker, living, hm);
               if (isMarked(living, attacker)) {
                  dmg *= 1.0F + hm * 0.08F;
               }
            }
            // Berserker (chestplate, below 50% HP) — attacking.
            int berk = levelOf(attacker.getItemBySlot(EquipmentSlot.CHEST), BERSERKER);
            if (berk > 0 && attacker.getHealth() <= attacker.getMaxHealth() * 0.5F) {
               dmg *= 1.2F;
            }
         }
         if (source.is(net.minecraft.tags.DamageTypeTags.IS_FALL) && instance instanceof ServerPlayer landing) {
            ItemStack chest = landing.getItemBySlot(EquipmentSlot.CHEST);
            int safe = chest.is(net.minecraft.world.item.Items.ELYTRA)
               ? CustomEnchantments.levelOf(chest, CustomEnchantments.SAFE_LANDING)
               : 0;
            if (safe > 0) {
               // Vanilla starts fall damage after roughly three blocks. Safe
               // Landing adds two blocks of genuine tolerance per level.
               dmg = Math.max(0.0F, dmg - safe * 2.0F);
            }
            // The Warden's Mantle: "Light as Air" cuts a fall to a quarter of what it was worth,
            // and this is the only place a fall's damage can be scaled rather than cancelled - the
            // same branch the Safe Landing enchantment already uses, for the same reason. Scaling
            // here rather than at the allow/blame step is what keeps the Mantle's second wind
            // honest: the item softens the fall first and answers only what is lethal after that.
            dmg = SeaAndSkyGear.scaleFallDamage(landing, dmg);
         }
         if (instance instanceof Player guardVictim && AdvancedEnchantments.tryPerfectGuard(guardVictim, source)) {
            dmg = 0.0F;
         } else if (instance instanceof Player guardVictim && AdvancedEnchantments.isGuardBlocking(guardVictim, source)) {
            dmg *= 0.5F;
         }
         // Berserker (chestplate, below 50% HP) — being hit: -8% armor effectiveness.
         if (instance instanceof ServerPlayer victim && victim.getHealth() <= victim.getMaxHealth() * 0.5F) {
            int berk = levelOf(victim.getItemBySlot(EquipmentSlot.CHEST), BERSERKER);
            if (berk > 0) {
               dmg *= 1.08F;
            }
         }
      } catch (Throwable t) {
         // never let enchant hooks break the pipeline
      }
      return dmg;
   }

   // ============================================================ chainfire / sniper shot helpers

   public static int extraChainArrows(ItemStack stack) {
      return isChainfireCrossbow(stack) ? 4 : 0;
   }

   /** Queue one extra chainfire shot so each arrow leaves on a later tick. */
   public static void queueChainfireShots(
      ServerLevel level,
      LivingEntity shooter,
      net.minecraft.world.InteractionHand hand,
      ItemStack weapon,
      List<ItemStack> projectiles,
      float velocity,
      float inaccuracy,
      boolean crit,
      LivingEntity target,
      int count,
      ChainfireShoot shoot
   ) {
      if (count <= 0 || projectiles == null || projectiles.isEmpty()) {
         return;
      }
      for (int i = 0; i < count; i++) {
         CHAINFIRE_QUEUE.addLast(new QueuedChainShot(level, shooter, hand, weapon.copy(), copyProjectiles(projectiles), velocity, inaccuracy, crit, target, (i + 1) * CHAINFIRE_DELAY_TICKS + 1, shoot));
      }
   }

   private static List<ItemStack> copyProjectiles(List<ItemStack> projectiles) {
      List<ItemStack> copies = new ArrayList<>(projectiles.size());
      for (ItemStack projectile : projectiles) {
         copies.add(projectile.copyWithCount(1));
      }
      return copies;
   }

   public static void tickChainfire() {
      Iterator<QueuedChainShot> iterator = CHAINFIRE_QUEUE.iterator();
      while (iterator.hasNext()) {
         QueuedChainShot shot = iterator.next();
         shot.delay--;
         if (shot.delay > 0) {
            continue;
         }
         iterator.remove();
         if (shot.shooter.isAlive() && shot.shooter.level() == shot.level) {
            shot.shoot.call(shot.level, shot.shooter, shot.hand, shot.weapon, shot.projectiles, shot.velocity, shot.inaccuracy, shot.crit, shot.target);
         }
      }
   }

   @FunctionalInterface
   public interface ChainfireShoot {
      void call(ServerLevel level, LivingEntity shooter, net.minecraft.world.InteractionHand hand, ItemStack weapon, List<ItemStack> projectiles, float velocity, float inaccuracy, boolean crit, LivingEntity target);
   }

   private static final class QueuedChainShot {
      private final ServerLevel level;
      private final LivingEntity shooter;
      private final net.minecraft.world.InteractionHand hand;
      private final ItemStack weapon;
      private final List<ItemStack> projectiles;
      private final float velocity;
      private final float inaccuracy;
      private final boolean crit;
      private final LivingEntity target;      private int delay;
      private final ChainfireShoot shoot;

      private QueuedChainShot(
         ServerLevel level,
         LivingEntity shooter,
         net.minecraft.world.InteractionHand hand,
         ItemStack weapon,
         List<ItemStack> projectiles,
         float velocity,
         float inaccuracy,
         boolean crit,
         LivingEntity target,
         int delay,
         ChainfireShoot shoot
      ) {
         this.level = level;
         this.shooter = shooter;
         this.hand = hand;
         this.weapon = weapon;
         this.projectiles = projectiles;
         this.velocity = velocity;
         this.inaccuracy = inaccuracy;
         this.crit = crit;
         this.target = target;
         this.delay = delay;
         this.shoot = shoot;
      }
   }

   /**
    * (No-op placeholder; the sniper flag is read off the arrow's weapon at hit
    * time, so no per-arrow stamping is required.)
    */
   public static void markSniperProjectile(net.minecraft.world.entity.projectile.Projectile arrow) {
      SNIPER_ARROWS.add(arrow.getId());
   }

   private static final java.util.Set<Integer> SNIPER_ARROWS =
      java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
   private static final java.util.Set<UUID> SNIPER_SHOOTERS =
      java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

   /**
    * True if the projectile was fired by a sniper crossbow. Checks the weapon's
    * per-arrow marker OR the static per-shooter fallback.
    */
   public static boolean isSniperProjectile(net.minecraft.world.entity.projectile.Projectile arrow) {
      if (arrow == null) {
         return false;
      }
      if (SNIPER_ARROWS.contains(arrow.getId())) {
         return true;
      }
      ItemStack weapon = arrow instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow aa ? aa.getWeaponItem() : null;
      return weapon != null && isSniperCrossbow(weapon);
   }

   public static void forgetProjectile(int id) {
      SNIPER_ARROWS.remove(id);
   }
}